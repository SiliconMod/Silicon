#!/usr/bin/env python3
"""Review a PR diff from the collect artifact through the zengate gateway, then post the review.

This is the stage-2 engine of the workflow_run two-stage scheme (PR #67 fix):
- stock anti，antongulin/robin@v2.7.2 cannot run on workflow_run (ROUND 174 evidence:
  action.yml has no PR/diff/artifact input; src/main.ts only handles pull_request /
  issue_comment and hard-returns for pull_request_target; any other event falls into
  "No matching trigger found. Skipping." -> zero output). So this repo-local driver
  reuses the same building blocks: .github/code-reviewer.md (instructions),
  .github/robin.yml (thresholds: skip-paths / max-diff-size / max-comments), and the
  zengate gateway (MODEL=big-pickle via a real `opencode serve` subprocess).

Flow:
  1. read artifact (meta.json + pr.diff)
  2. filter out skip-paths file sections; truncate head-only to max-diff-size (same
     semantics as robin's slice(0,N) + marker)
  3. system = code-reviewer.md + JSON schema; user = filtered+truncated diff
  4. call gateway {GATEWAY_URL}/chat/completions (non-stream; proven through robin's
     blockingChatCompletion path, see AGENTS.md R69 run 36334307574)
  5. parse JSON findings; retry once when 0 findings + short summary (mirror robin's
     shouldRetryStructuredReview without JSON mode)
  6. post best-effort inline comments (cap max-comments, 422-tolerant) then a formal
     COMMENT review (advisor mode; request-changes=false in .github/robin.yml)

Exit codes: 0 on success (including "nothing to flag"); 1 on hard failure (missing
artifact, gateway error after retries, review post rejected 4xx/5xx).

Log fingerprints for CI verification (AGENTS.md: never rely on conclusion alone):
  "review from diff: N findings" / "Posted review <id>" / per-comment "posted line
  comment on <path>:<line>".
"""

import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

import yaml

CONFIG_PATH = ".github/robin.yml"
INSTRUCTIONS_PATH = ".github/code-reviewer.md"
TRUNCATED_MARKER = "\n\n[... Diff truncated due to size limit]"

JSON_SCHEMA_HINT = (
    "\n\n严格按照下面的 JSON 结构输出审查结果（不要输出任何 JSON 以外的文字）：\n"
    '{"summary": "总的审查结论（1-3 句，中文）", '
    '"findings": [{"severity": "high|medium|low", "path": "文件路径", '
    '"line": 行号数字, "body": "问题描述与建议（中文）"}]}\n'
    "没有发现时 findings 为空数组 []。"
)


def log(msg):
    print(msg, flush=True)


def run_gh(args, timeout=60, stdin_data=None):
    """Run `gh api ...`; return (exit_code, stdout). Never raises on HTTP errors."""
    env = dict(os.environ)
    if "GH_TOKEN" not in env:
        log("error: GH_TOKEN not set")
        return 1, ""
    try:
        p = subprocess.run(
            ["gh", "api", *args],
            input=stdin_data,
            capture_output=True, text=True, timeout=timeout, env=env,
        )
        return p.returncode, p.stdout.strip()
    except subprocess.TimeoutExpired:
        log("error: gh api timed out after %ss" % timeout)
        return 1, ""
    except Exception as exc:  # noqa: BLE001 - surface anything to the caller
        log("error: gh api failed: %s" % exc)
        return 1, ""


def load_config(repo_root="."):
    path = os.path.join(repo_root, CONFIG_PATH)
    if not os.path.exists(path):
        log("warning: %s not found, using defaults" % CONFIG_PATH)
        return {"skip-paths": [], "max-diff-size": 80000, "max-comments": 12, "request-changes": False}
    with open(path, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f) or {}
    return {
        "skip-paths": cfg.get("skip-paths") or [],
        "max-diff-size": int(cfg.get("max-diff-size") or 80000),
        "max-comments": int(cfg.get("max-comments") or 12),
        "request-changes": bool(cfg.get("request-changes", False)),
    }


def path_matches_skip(path, patterns):
    for pat in patterns:
        if fnmatch.fnmatch(path, pat):
            return True
    return False


def sanitize_text(s):
    """输出净化（P1 边界评审点 8 的驱动侧防线）：剥掉控制字符，
    防 diff/模型输出里的注入串污染评论。diff 内容只进 LLM，模型输出
    只以 COMMENT 形式呈现，任何内容都不被执行。"""
    if not isinstance(s, str):
        return ""
    return re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", s).strip()


def filter_and_truncate(diff, skip_patterns, max_diff_size):
    """Split diff into per-file sections on 'diff --git ', drop skip-path files,
    then keep the first max_diff_size chars of the remainder (head-only, robin semantics)."""
    out = []
    cur = []
    cur_path = None
    lines = diff.splitlines(keepends=True)

    def flush():
        nonlocal cur, cur_path
        if cur and cur_path is not None and not path_matches_skip(cur_path, skip_patterns):
            out.append("".join(cur))
        cur, cur_path = [], None

    for ln in lines:
        if ln.startswith("diff --git "):
            flush()
            m = re.search(r" b/(\S+)", ln)
            cur_path = m.group(1) if m else None
            cur.append(ln)
        else:
            cur.append(ln)
    flush()

    joined = "".join(out)
    if len(joined) > max_diff_size:
        joined = joined[:max_diff_size] + TRUNCATED_MARKER
        log("diff truncated to %d chars (original %d)" % (max_diff_size, len("".join(out))))
    return joined


def build_messages(instructions, diff):
    system = instructions.rstrip() + JSON_SCHEMA_HINT
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": "以下是本次 PR 的 diff（可能被截断）：\n\n" + diff},
    ]


def call_llm(gateway_url, model, messages, timeout=600):
    body = json.dumps({
        "model": model,
        "messages": messages,
        "temperature": 0.1,
        "stream": False,
    }).encode("utf-8")
    req = urllib.request.Request(
        gateway_url.rstrip("/") + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")[:500]
        log("error: gateway HTTP %s: %s" % (exc.code, raw))
        return None
    except Exception as exc:  # noqa: BLE001
        log("error: gateway call failed: %s" % exc)
        return None
    try:
        return data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        log("error: unexpected gateway response shape")
        return None


def extract_json(text):
    if not text:
        return None
    t = text.strip()
    if t.startswith("```"):
        t = re.sub(r"^```[a-zA-Z]*\n?", "", t)
        t = re.sub(r"\n?```$", "", t)
    try:
        return json.loads(t)
    except json.JSONDecodeError:
        pass
    m = re.search(r"\{.*\}", t, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(0))
        except json.JSONDecodeError:
            return None
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--artifact-dir", required=True)
    ap.add_argument("--pr", required=True)
    ap.add_argument("--head-sha", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--run-id", help="collect run id (from workflow_run context); "
                    "artifact meta.run_id must equal it or the run fails closed")
    ap.add_argument("--dry-run", action="store_true", help="print findings without posting")
    ap.add_argument("--json-out", help="write parsed findings JSON to this file (for tests)")
    ap.add_argument("--repo-root", default=".",
                    help="repo root containing .github/robin.yml and .github/code-reviewer.md (CI: checkout 根目录)")
    args = ap.parse_args()

    meta_path = os.path.join(args.artifact_dir, "meta.json")
    diff_path = os.path.join(args.artifact_dir, "pr.diff")
    if not (os.path.exists(meta_path) and os.path.exists(diff_path)):
        log("error: artifact missing (need meta.json + pr.diff in %s)" % args.artifact_dir)
        return 1

    with open(meta_path, "r", encoding="utf-8") as f:
        meta = json.load(f)
    with open(diff_path, "r", encoding="utf-8") as f:
        raw_diff = f.read()

    # P1 指纹校验（fail-closed）：artifact meta 必须与调用方（workflow_run 上下文）
    # 完全一致——artifact 只当 diff 内容，其 PR 号/link 从不用于任何 API 调用。
    checks = [
        ("head_sha", meta.get("head_sha"), args.head_sha),
        ("number", str(meta.get("number")), str(args.pr)),
    ]
    if args.run_id:
        checks.append(("run_id", str(meta.get("run_id")), args.run_id))
    for k, got, want in checks:
        if got != want:
            log("error: artifact meta mismatch on %s: meta=%r context=%r" % (k, got, want))
            return 1

    if meta.get("draft"):
        log("PR is a draft; skipping review")
        return 0

    # P3 去重：同一 head 已由本 bot 发过 COMMENT review 就不重复发。
    # concurrency 只取消在途 run，这里兜掉已落地 review 的重复触发（一次 synchronize 一条）。
    rc, out = run_gh(["repos/%s/pulls/%s/reviews" % (args.repo, args.pr)])
    if rc == 0:
        try:
            existing = json.loads(out)
        except json.JSONDecodeError:
            existing = []
        for rv in existing:
            if (rv.get("user") or {}).get("login") == "github-actions[bot]" \
                    and rv.get("state") == "COMMENTED" \
                    and rv.get("commit_id") == args.head_sha:
                log("head %s already reviewed (review #%s); skipping" % (args.head_sha[:12], rv.get("id")))
                return 0
    else:
        log("warning: could not list existing reviews (rc=%s); proceeding" % rc)

    cfg = load_config(args.repo_root)
    diff = filter_and_truncate(raw_diff, cfg["skip-paths"], cfg["max-diff-size"])
    log("diff bytes after filter: %d" % len(diff.encode("utf-8")))

    with open(os.path.join(args.repo_root, INSTRUCTIONS_PATH), "r", encoding="utf-8") as f:
        instructions = f.read()
    messages = build_messages(instructions, diff)

    gateway_url = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8083/v1")
    model = os.environ.get("MODEL", "big-pickle")

    result = None
    for attempt in (1, 2):
        log("llm attempt %d (model=%s)" % (attempt, model))
        content = call_llm(gateway_url, model, messages)
        parsed = extract_json(content)
        if parsed is None:
            log("attempt %d: failed to parse JSON from LLM" % attempt)
            continue
        findings = parsed.get("findings") or []
        summary = (parsed.get("summary") or "").strip()
        log("attempt %d: parsed %d findings, summary=%r" % (attempt, len(findings), summary[:60]))
        # mirror robin's shouldRetryStructuredReview: 0 findings + short summary => retry once
        if len(findings) > 0 or len(summary) > 40:
            result = parsed
            break
        log("attempt %d: empty findings with short summary -> retrying" % attempt)
    if result is None:
        # both attempts empty/short: treat as "nothing worth flagging" (advisor mode),
        # but keep the review visible so the pipeline output is observable.
        result = {"summary": "未发现值得指出的问题", "findings": []}
        log("review from diff: 0 findings (fallback after retries)")

    findings = result["findings"] or []
    summary = sanitize_text(result.get("summary") or "")
    log("review from diff: %d findings" % len(findings))

    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)

    if args.dry_run:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0

    max_comments = cfg["max-comments"]
    post_inline = 0
    for fd in findings[:max_comments]:
        path = sanitize_text(fd.get("path") or "")
        line = fd.get("line")
        body = sanitize_text(fd.get("body") or "")
        if not path or not line or not body:
            continue
        payload = {
            "commit_id": args.head_sha,
            "path": path,
            "line": int(line),
            "body": body,
        }
        # 【修 2026-09-30】此前只构建了 payload 却从未把它送进 stdin ——
        # `gh api --input -` 收到空 body，行内评论 8/8、5/5 全部被 422 拒收，
        # 每条审查只剩摘要 body。现在把 JSON 以 stdin 传入，行内评论真正落地。
        rc, out = run_gh([
            "-X", "POST", "repos/%s/pulls/%s/comments" % (args.repo, args.pr),
            "--input", "-",
        ], stdin_data=json.dumps(payload))
        if rc == 0:
            post_inline += 1
            log("posted line comment on %s:%s" % (path, line))
        else:
            # 422: line not on a changed line / path mismatch; skip, don't kill the review
            log("warning: inline comment on %s:%s failed (rc=%s): %s" % (path, line, rc, out[:200]))

    review_body = ("### 自动审查结果（Robin Review / workflow_run 二段式）\n\n"
                   + summary + "\n\n共 %d 条发现，%d 条已发为行内评论。\n"
                   % (len(findings), post_inline))
    rc, out = run_gh([
        "-X", "POST", "repos/%s/pulls/%s/reviews" % (args.repo, args.pr),
        "-f", "event=COMMENT",
        "-f", "body=%s" % review_body,
    ])
    if rc != 0:
        log("error: posting review failed (rc=%s): %s" % (rc, out[:300]))
        return 1
    try:
        rid = json.loads(out).get("id")
    except json.JSONDecodeError:
        rid = out
    log("Posted review %s on PR #%s" % (rid, args.pr))
    return 0


if __name__ == "__main__":
    sys.exit(main())