#!/usr/bin/env python3
"""投票通过则 squash 合并。本脚本是合并闸门，独立复核票数，不信任 democracy job 的退出码。

为什么必须独立复核：action 的 switch 没有 default 分支，任何落不进
{opened,reopened,synchronize,closed} 的事件都会「什么都不做 → job 成功」，
产出并非真实评估的绿色 check。已实测遇到过（payloadAction='submitted'，
全日志 voting failure message / numVoters / Current Voting Result 计数均为 0）。
合并不可逆，绝不能只凭「上一个 job 是绿的」就执行。

计票语义（action 侧，Xiaobei09/git-democracy）：
  reactions.ts  遍历 pulls.listReviews，每人最后一次 review 覆盖前一次
                (result.set(login, vote))；PR 作者被自动记为 +1
  reactions.ts  weight = voters.get(user) ?? 0；weight>0 且 vote!=0 才计入 numVoters
  voting.ts     percentage = for/(for+against)*100

【本脚本与 action 的分歧清单】R84 已逐条穷举核对，方向分三类：
  一律更严：0 票显式判失败、更新后旧票作废（action 不做时间过滤）、
            解析不出时间戳的票按作废处理、merge 时把 sha 钉死到复核过的头提交、
            权重非整数/外层留空的嵌套写法直接停下、浮点/科学计数/十六进制权重
            （action 接受为 number）一律拒。
  同口径：最新票用 reviews 单调递增 id 破平，与 action 的遍历顺序一一对应。
  **更宽（已知缺陷，均在 PR 描述里给了提案，未擅自改）**：
    a) 重复键静默取后者（action 的 js-yaml 直接抛 duplicated mapping key）；
    b) 嵌套写法中外层键带整数值时，拍平后内层真人权重被本脚本认作、action 认不出；
    c) .voters.yml 带 BOM 且首行不是 `---` 时，首个投票人的键被改名而消失
       （action 的 js-yaml 会剥 BOM）—— 若消失的是反对者，那条反对就没了。
  下述第 6 条即 (b) 的现状说明，历史上曾被记成「唯一一处漏洞、已由 bad_weights
  堵住」，实测证明那道防线只挡住一半写法。
1. 0 票时 action 得 0/0=NaN，NaN<100 为 false 会静默放行；这里显式判失败。
2. action 的 weightedVoteTotaling 循环里没有任何时间过滤 —— PR 更新后
   历史票依然全部计入。这里按需求改为：**PR 最近一次更新之前的票一律作废，
   但作者自投的那票保留**（作者无法 review 自己的 PR，该票是 action 合成的，
   不受时间限制）。投票窗口也改为从「PR 最近一次更新」起算，
   而不是 action 用的 head.repo.pushed_at（那是头仓任意分支的 push 时间，
   别人推别的分支也会把它顶掉，并不等于本 PR 的更新时间）。
3. action 判定通过后由人点合并；这里用 REST merge 并**把 sha 钉死到本次复核过的
   头提交**，堵住「复核完到合并前被推了新提交」的 TOCTOU 窗口（`gh pr merge`
   合的是当时的当前 head，不是我们复核过的那个）。
4. 「同一人多次 review 取最后一次」原本靠 submitted_at 的字符串比大小，而它只有
   **秒级**精度 —— 同一秒内投两次会被判成「时间相同」而保留先投的那张，与 action
   的 result.set 后写覆盖相反，**且方向是比 action 更宽**（先赞成后请求修改 →
   本脚本当成赞成而放行）。现改用 reviews 的单调递增 id 破平，与 action 遍历顺序对齐。
5. 时间戳解析不出来的票不再默认为「最新票」，而是与过期票一同作废 —— 解析不出来
   就无法证明它晚于本次更新，按 fail-closed 处理。
6. .voters.yml 的权重若不是整数（多半是文件被改成了平坦解析器读不了的嵌套写法），
   脚本停下并说明，而不是拿着自己都没读对的配置去放行合并。
   ⚠️ R84 更正：这条防线**只挡住「外层键留空」那一种嵌套写法**。`voters: 1`
   换行 `  rt334: 3` 时外层键通过整数检查，内层真人 rt334 会被本脚本认作权重 3
   的投票人，而 action 认不出他（其值为 object，被 `typeof val === 'number'` 滤掉）。
   详见下方 bad_weights 处的注释。

【rt334 评审的落实】2026-09-28 维护者 rt334 提了四条建议（明说「都不是阻塞项」，
且认同设计取向），逐条对应：
  ① 删贡献者 fork 分支 -> 改为【默认关】，须维护者设仓库变量
     VOTE_MERGE_DELETE_FORK_BRANCH=1 才删（见 delete_head_branch）。
  ② api() 无超时 -> 加 timeout=30 + 有限重试（见 api 的 docstring）。
  ③ 缺键时默认门槛 1 / 0 会【放宽】闸门 -> 改取文档门槛 3 / 10 并打警告
     （见 _int_cfg）。这是本文件唯一一处【收紧】方向的配置变更。
  ④ 输出里只有「未合并（<原因>）」，维护者要自己翻 review 列表才知道被谁挡住
     -> 新增「- 阻塞明细:」逐条指名是谁的哪一张 review（见 main 末尾）。
  ①②④ 与判定逻辑无关（纯安全面 / 纯诊断），③ 收紧且生产配置三个键齐全、
  行为不变 —— 即收紧只作用于「配置写错」这一种此前静默通过的状态。

【退出码约定】——两类结局必须可区分，否则「合并闸门跑过了」会被误读成「合并了」：
  0 = 脚本正常跑完并做出明确判定（已合并 / 判定不合并：草稿、非 open、票未达标）
  1 = 无法判定或动作失败（环境变量缺失、取不到更新时间、合并请求被拒）
"拿不到数据" 一律 fail-closed（退出 1、不合并），绝不静默放行。
每条路径都打印一行以「合并结果: 」开头的指纹，便于在 run 日志里 grep 确认
到底走了哪条（不能只看 job 的 conclusion）。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

APPROVED, CHANGES_REQUESTED = "APPROVED", "CHANGES_REQUESTED"
PENDING = "PENDING"
RESULT_PREFIX = "合并结果: "

# 每次 API 调用的超时与重试（rt334 评审建议 2）。没有超时的话，GitHub 侧静默
# 挂起会让 job 一直挂到 workflow 上限，而闸门卡住 = PR 永远 BLOCKED。
API_TIMEOUT_SECONDS = 30
API_TIMEOUT_RETRIES = 2
API_RETRY_BACKOFF_SECONDS = 2

# 合并成功后是否删掉【贡献者 fork 上的】分支（rt334 评审建议 1）。默认关：
# 跨仓 PR 时 repo 取自 head.repo.full_name，就是作者自己的 fork；只要作者勾了
# "Allow edits by maintainers"，base 仓的 token 就能删掉对面 fork 上的分支。
# 有些作者还要留着 rebase、或同一分支还挂着别的 PR。改为显式开启。
#
# 【为什么只存变量名、值在调用时现读，而不是在 import 时算成布尔常量】
# import 时求值等于把这个开关永久冻结在进程启动的那一刻：测试无法用环境变量
# 驱动它（只能去改模块属性），将来若有人在本文件里 import 后再改环境变量也会
# 静默失效 —— 那是「看起来可控、实际不可控」的一类陷阱。存名字 + 调用时读，
# 既能被 grep 到，也让 env 始终是唯一事实来源。
DELETE_FORK_BRANCH_VAR = "VOTE_MERGE_DELETE_FORK_BRANCH"

# 跨仓 PR 自动合并用的本仓凭据名（rt334 评审建议 2 的落地）。
#
# 【为什么这里必须有 key，job 级 permissions 却救不了】
# GitHub 会把 fork 来源 pull_request / pull_request_review 事件注入的 GITHUB_TOKEN
# 降级为只读，且这条安全规则【盖不住】：workflow 里给 automerge job 显式写
# `permissions: contents: write` 也不行。（已实测：AGENTS.md R72 的单变量对照，
# 同一 PR 同一头提交，只换事件类型。）
# 于是「票数已够 -> PUT /pulls/{n}/merge」这一步在跨仓 PR 上结构性 403，
# 而本仓近 12 个 PR 全部 isCrossRepository=true。要真正实现自动合并，合并动作
# 需要一把本仓凭据。
#
# 【为什么不能用 GITHUB_TOKEN 代替 PAT】
# GitHub 规定：GITHUB_TOKEN 发出的 push【不会】触发 on: push 工作流。而本仓
# commitTest.yml 正是靠 push 到 test 来构建 jar + 删旧预发布 + 建新预发布。
# 人工点合并时 push 事件正常触发（点击者是人），改成 token 合并后该链路静默
# 消失 —— 表现为「PR 合上了但没有新预发布」，且没有任何报错。PAT 不受此限。
# 同仓 PR 不需要它（GITHUB_TOKEN 权限完整），因此优先用 github.token。
#
# 与 DELETE_FORK_BRANCH_VAR 同样只存变量名、值在调用时现读。
MERGE_TOKEN_VAR = "AUTOMERGE_TOKEN"


def delete_fork_head_branch_enabled():
    return os.environ.get(DELETE_FORK_BRANCH_VAR) == "1"


def api(path, token, method="GET", body=None):
    """调 GitHub REST。

    【必须有超时】urlopen 默认无限等待：GitHub 侧静默挂起时 job 会一直挂着直到
    workflow 上限（默认 6 小时），而闸门卡住会让 PR 永远 BLOCKED —— 正是本 PR
    想消灭的那类「没有事件来重新评估」（rt334 评审建议 2）。

    【重试只针对「同一次调用重发是安全的」的情形】
    - 网络层错误（URLError / socket 超时）与 5xx / 429：服务端要么没收到、要么
      没处理完，重发安全。
    - 4xx 不重试：403（权限/限流）与 409（头提交已变）的语义不同，重发只会把
      「无法判定」拖成超时，且 409 必须原样冒泡给 merge_pr 的分流诊断。
    - 只对幂等方法（GET/HEAD）重试。带 body 的 PUT（merge）重发是**不安全**的：
      第一次可能已经合并成功，只是响应没回来，再发一次会撞 405/409 并让诊断失真。
    """
    data = None if body is None else json.dumps(body).encode("utf-8")
    last = None
    for attempt in range(API_TIMEOUT_RETRIES + 1):
        req = urllib.request.Request(
            "https://api.github.com" + path,
            data=data,
            method=method,
            headers={
                "Authorization": "Bearer " + token,
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=API_TIMEOUT_SECONDS) as r:
                raw = r.read()
                # 必须先读原始字节再判空，不能直接 json.load(r)：
                # DELETE /git/refs/heads/<ref> 成功时返回 **204 No Content**（响应体
                # 为空），json.load 读到空体抛 JSONDecodeError。它是 ValueError 的
                # 子类，**既不是 HTTPError 也不是 OSError**，本函数的 except 一条都
                # 接不住，会一路冒到 delete_head_branch 的 `except Exception`，把一次
                # 成功的删除报成「清理异常（不影响合并）」——分支真删了，维护者却看到
                # 失败提示，会去查一个不存在的故障（与 405 文案那处同族：报错误导方向）。
                # 空体按「成功但无内容」处理；非空但坏掉的 JSON 仍照常抛，不被掩盖。
                return json.loads(raw) if raw.strip() else None
        except urllib.error.HTTPError as e:
            # 4xx（含 403/409）一律原样抛出，交给调用方按语义分流。
            if e.code < 500 and e.code != 429:
                raise
            last = e
        except (urllib.error.URLError, OSError) as e:
            last = e
        if method not in ("GET", "HEAD") or attempt == API_TIMEOUT_RETRIES:
            raise last
        print("- API 重试 %d/%d（%s %s，上次失败：%s）"
              % (attempt + 1, API_TIMEOUT_RETRIES, method, path,
                 getattr(last, "code", type(last).__name__)))
        time.sleep(min(API_RETRY_BACKOFF_SECONDS * (attempt + 1), 10))
    raise last


def parse_ts(s):
    """解析 GitHub 的时间戳。

    无法解析时**返回 None 而不是抛异常** —— 调用方据此 fail-closed；
    若这里抛 ValueError，下面 `if update_time is None` 那道守卫就永远走不到，
    等于把「安全兜底」变成了「崩溃路径」。
    """
    if not s:
        return None
    s = str(s).strip()
    for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%S.%fZ"):
        try:
            dt = datetime.strptime(s, fmt)
        except ValueError:
            continue
        return dt.replace(tzinfo=timezone.utc) if dt.tzinfo is None else dt.astimezone(timezone.utc)
    return None


def load_yaml_map(path, default=None):
    """只支持 name: number 这种平坦映射，够 .voters.yml / .voting.yml 用。"""
    out = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].rstrip()
                if not line or line in ("---", "..."):
                    continue
                k, _, v = line.partition(":")
                k, v = k.strip().strip("\"'"), v.strip().strip("\"'")
                if not k:
                    continue
                try:
                    out[k] = int(v)
                except ValueError:
                    out[k] = v
    except FileNotFoundError:
        if default is None:
            raise
        return out
    except UnicodeDecodeError as exc:
        # E2E 审查建议：配置解码失败必须显式 fail-closed，不能静默拍平、
        # 也不能裸崩成 traceback——给出一条能定位文件和原因的拒绝信息。
        raise RuntimeError("%s 不是有效 UTF-8（%s）——配置损坏，拒绝评估"
                           % (path, exc)) from exc
    return out


def latest_reviews(reviews):
    """每人最后一次 review 胜出，复刻 action 的 result.set(login, vote) 覆盖语义。

    【谁算「最后」】是 action 的遍历顺序（GitHub API 返回顺序 = id 递增），所以这里
    必须按【id 最大】选胜者，不能按 submitted_at 最大。两者在两类场景下会选出不同的
    最终票，方向可偏「放行」（安全相关）：
      1. 同一秒内连投两次：submitted_at 字符串相等，只比时间会保留先投的那张；
      2. 先以草稿打开、之后才提交（id 小但 submitted_at 晚）：在它之后还有一张
         已提交的 review 时，按 submitted_at 会把草稿之后的提交判成「旧票」。
    id 递增即提交顺序，与 action 的遍历顺序一一对应 —— 这是本函数与 action 保持
    同口径的唯一正确键。差异族已被差分对拍机器钉住（checks/diff-vote-tally.py）。
    """
    latest = {}
    for r in reviews:
        login = (r.get("user") or {}).get("login")
        if login is None:                      # action: not counting vote of null user
            continue
        if r.get("state") == PENDING and r.get("submitted_at") is None:
            # 未提交的 review 草稿（state==PENDING 且 submitted_at==null）不参与计票，
            # 与 action a0c7f646 的「not counting unsubmitted PENDING review draft」
            # 一致。若把它算进来，它（vote=0、id 更大）会顶掉这人真实的最近一票。
            continue
        state = r.get("state")
        vote = 1 if state == APPROVED else (-1 if state == CHANGES_REQUESTED else 0)
        sub = r.get("submitted_at")
        rid = r.get("id") or 0
        prev = latest.get(login)
        if prev is not None and prev[2] >= rid:
            continue                          # 只保留 id 最大的那次 = action 最后写到的那次
        # 带上 state：诊断输出要指名「哪一张 review、谁投的、什么时候」，
        # 只留 (vote, sub, rid) 的话 COMMENTED / DISMISSED / PENDING 三种
        # 都被压成 vote=0，运维看到的就只是「他不计入」而不知道他干了什么。
        latest[login] = (vote, sub, rid, state, r.get("commit_id") or "")
    return latest


# 作者合成票的 state 字面量。两处引用（默认元组与 _review_ref 的比较）必须同源，
# 否则不同步时不会报错，只会静默退化成「…，review #无，提交于 无」的错误诊断
# ——而那恰恰是「为什么合不掉」最需要说清的一句话（robin 二轮 L4）。
AUTHOR_SYNTH_STATE = "作者合成票（action 替他投）"
AUTHOR_SYNTH_DEFAULT = (None, None, None, AUTHOR_SYNTH_STATE, None)


def tally(reviews, voters, author, update_time=None, now=None, head_sha=None):
    """返回 (numVoters, forIt, againstIt, per_user, stale_dropped, per_vote, dropped_detail)。

    update_time 之后提交的票才有效；作者的合成票恒有效。

    后两个返回值是【纯诊断素材】，不参与任何门槛判定（rt334 评审建议）：
    维护者看到「未合并（赞成率 66.7% < 门槛 100%）」时，还得自己去翻 review 列表
    才能定位到具体是谁的哪一票，而那正是「为什么合不掉」的答案。
      per_vote       = {login: (vote, weight, state, submitted_at, review_id)}，
                       覆盖【所有】进入计票的人（含权重 0 的与作者合成票），
                       用来指名「是谁的哪一张 review 在挡着」。
      dropped_detail = {login: (state, submitted_at, review_id)}，
                       那些因未落在当前头提交上（head_sha 缺省时改按「早于本次
                       更新 / 时间戳解析不出来」兜底）而作废的票 ——
                       「我明明投了票为什么不算数」的答案在这里。
    位置都排在原 5 元组之后，索引 0..4 的含义一字未动。
    """
    latest = latest_reviews(reviews)
    counted, dropped = {}, []
    dropped_detail = {}
    for login, (vote, sub, rid, state, cmt) in latest.items():
        t = parse_ts(sub)
        # vote==0（PENDING / COMMENTED / DISMISSED）不参与任何门槛判定，它的时间戳
        # 解析不了也无所谓；只有决定性的票才必须过锚点这一关。
        if vote == 0:
            counted[login] = vote
            continue
        stale = False
        # 锚点①（不可伪造，E2E 审查主发现）：决定性票必须落在当前 head 提交上。
        # GitHub 在提交 review 时把 commit_id 钉到当时的 head commit，PR 作者
        # 无法回填这个字段 —— 旧头提交上的 APPOVED 在 head 前移后自然作废，
        # 作者也不能靠改提交者时间把旧票「洗」成新票。head_sha 传空时退回锚点②。
        if head_sha is not None and cmt != head_sha:
            stale = True
        elif update_time is not None and (t is None or t <= update_time):
            # t is None 一并作废：时间戳解析不出来，就【无法证明】这张票晚于本次
            # 更新，按 fail-closed 处理。若放行，条件就退化成「解析失败=最新票」，
            # 那恰好是本函数存在的意义所在 —— 防止 PR 更新前的旧票被算进来。
            stale = True
        if stale:
            dropped.append(login)             # 旧头 / 过早的票 → 作废
            dropped_detail[login] = (state, sub, rid)
            continue
        counted[login] = vote
    if author:                                 # 作者无法 review 自己，action 替他投 +1；恒有效
        counted[author] = 1
        if author in dropped:
            dropped.remove(author)
            dropped_detail.pop(author, None)

    num_voters = for_it = against_it = 0
    per_user = {}
    per_vote = {}
    for login, vote in counted.items():
        w = voters.get(login)
        w = w if isinstance(w, int) else 0
        e = latest.get(login) or AUTHOR_SYNTH_DEFAULT
        state, sub, rid = e[3], e[1], e[2]
        per_vote[login] = (vote, w, state, sub, rid)
        # 权重非正一律【跳过】，绝不按其原值参与求和（robin 二轮 M1）。
        # 负权重是 fail-open 的入口：num_voters 要求 w>0，for_it/against_it 却按
        # `vote * w` 累加、total = for_it + against_it。一个权重 -1 的登记投票人投反对，
        # against_it 被压成 -1 ⇒ total 反而小于 for_it ⇒ 赞成率恒 100%，
        # 而该人因 w<=0 又不计入 num_voters ⇒ 人数与赞成率两道闸门同时失效。
        # 与「分歧一律往更严」相反。加载处已对负值 fail-closed 拒绝（见 main），
        # 这里再守一道是防御性的：tally 可能被别的调用方直接喂原始 voters。
        if w <= 0:
            continue
        if vote != 0:
            num_voters += 1
            per_user[login] = (vote, w)
        if vote > 0:
            for_it += vote * w
        elif vote < 0:
            against_it += -vote * w
    return (num_voters, for_it, against_it, per_user, dropped,
            per_vote, dropped_detail)


def pick_merge_token(base_repo, head_repo, gh):
    """挑一把能真正完成合并的凭据，返回 (token 或 None, 给人看的说明)。

    为什么要挑而不是直接用 GITHUB_TOKEN —— 见 MERGE_TOKEN_VAR 处的完整论证，
    这里只留判定逻辑与三条分支的理由。

    拓扑判定用 head_repo：
      · head_repo 等于 base_repo      -> 同仓，GITHUB_TOKEN 权限完整，用它
      · head_repo 已知且不同          -> 跨仓 fork，GITHUB_TOKEN 必被降级成只读，
                                        改用 MERGE_TOKEN_VAR
      · head_repo 拿不到（空值）      -> 【按同仓处理】。这是有意的保守方向：
                                        拿不到拓扑时不能断定「跨仓」，而误判成
                                        「跨仓」会逼维护者去配一把其实用不上的
                                        凭据；误判成「同仓」的最坏结果只是
                                        合并时吃一个 403，且 403 分支会明写
                                        成因，排查方向不会跑偏。
    """
    pat = (os.environ.get(MERGE_TOKEN_VAR) or "").strip()
    same_repo = (not head_repo) or (head_repo == base_repo)
    if same_repo:
        return gh, ("本仓 PR（GITHUB_TOKEN 权限完整，无需外部凭据）")
    if pat:
        return pat, ("跨仓 fork PR —— GITHUB_TOKEN 在此被 GitHub 降级为只读，"
                     "改用 %s：%s" % (MERGE_TOKEN_VAR, _PAT_WHY))
    return None, ("跨仓 fork PR 且未配置 %s —— GITHUB_TOKEN 在此是只读的，"
                  "合并必然 403。请维护者在本仓 Settings → Secrets and variables"
                  " → Actions → Secrets 里新增 %s。%s"
                  % (MERGE_TOKEN_VAR, MERGE_TOKEN_VAR, _PAT_WHY))


# PAT 相对 GITHUB_TOKEN 的关键理由，单独成常量以便日志与文档共用同一份说法。
_PAT_WHY = ("选它而不是继续用 GITHUB_TOKEN 的关键理由：GITHUB_TOKEN 发出的 push "
            "【不会】触发 on: push 工作流，而本仓 commitTest.yml 正是靠 push 到 "
            "test 来构建 jar + 删旧预发布 + 建新预发布 —— 换成 token 合并后该链路"
            "会静默消失，症状是「PR 合上了但没有新预发布」，且全程零报错。")


def merge_pr(repo, pr, sha, token, pr_head_repo=None):
    """用 REST merge 把 sha 钉死到已复核的头提交。

    返回 (merged: bool, detail: str, code: int|None, merge_token: str|None)。
    第 4 项 = 合并实际使用的凭据（跨仓 PR 时是 pick 出来的 AUTOMERGE_TOKEN 而非
    只读的 GITHUB_TOKEN），供删贡献者分支时复用 —— 旧实现删分支用的还是裸
    GITHUB_TOKEN，跨仓删分支路径必然 403（E2E 审查「合并凭据未复用」发现）。
    传了 sha 之后，若在
    「复核完成」到「合并生效」之间有人推了新提交，GitHub 会返回 409 而**不会**
    把没复核过的新提交合进去 —— 这正是 gh pr merge 留给我们的 TOCTOU 窗口。

    必须把 HTTP 状态码带出去：合并被拒的三种原因**处置方式完全不同**，而 GitHub
    对其中两种都只回一句含义模糊的 message，不看状态码必然误诊：
      403 = 令牌没有 contents: write（workflow 权限配错，或头在 fork 上跨仓写不通）
      405 = 合入前的约束不满足：ruleset / required check 挡住，或合并方法不被仓库
        允许（如未开 squash 却请求 squash merge，原始 message 会指明方法）
      409 = 头提交在复核后被改过（TOCTOU，脚本按设计拒绝，属正常竞态）
    """
    path = "/repos/%s/pulls/%s/merge" % (repo, pr)
    mt, why = pick_merge_token(base_repo=repo, head_repo=pr_head_repo, gh=token)
    print("- 合并凭据: %s" % why)
    if not mt:
        return False, ("无法判定：缺少可用的合并凭据"
                       "（详见上方「合并凭据」行）"), None, None
    try:
        out = api(path, mt, method="PUT",
                  body={"sha": sha, "merge_method": "squash"})
        return bool(out.get("merged")), (out.get("message") or ""), 200, mt
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail).get("message", detail)
        except ValueError:
            pass
        return False, detail.strip(), e.code, mt
    except (urllib.error.URLError, OSError) as e:
        # 网络层失败，不是「GitHub 明确拒绝」。api() 文档自己承认「带 body 的 PUT
        # 第一次可能已经合并成功，只是响应没回来」，那正是 URLError/OSError 而非
        # HTTPError 的场景。让它逃出去会变成裸 traceback + 退出码 1，维护者看到
        # 红叉以为合并失败，实际 PR 可能已经合上 —— 与 delete_head_branch 注释里
        # 明确要避免的「事后失败比直接报错更具误导性」同一类问题。
        # code=None 是「无法判定」的标记，main 会为它走独立分支（robin 二轮 M2/L3）。
        return False, ("无法判定：%s。请求可能在服务端已生效，"
                       "请先确认 PR 状态再决定是否重跑，勿直接重试合并"
                       % e), None, mt


def delete_head_branch(head, base_repo, token):
    """合并成功后尽力删掉贡献者分支。删不掉不影响合并结果，只提示。

    这里必须吞掉【所有】异常：合并此刻已经生效，若清理阶段的网络抖动让异常
    逃出去，job 会以退出码 1 收场 —— 维护者会看到「合并失败」的红叉，
    而 PR 其实已经合上了。这类「事后失败」比直接报错更有误导性。

    【跨仓时默认【不】删 —— 须显式开启】（rt334 评审建议 1）
    repo 取自 head.repo.full_name，跨仓 PR 时它就是【作者自己的 fork】。只要作者
    勾了 "Allow edits by maintainers"，base 仓的 token 就能删掉对面 fork 上的分支，
    而有些作者还要留着 rebase、或同一分支还挂着别的 PR —— 合并后顺手删别人的分支
    超出「合并一个 PR」的授权范围。开启方式（维护者一次性设置，默认关）：
        仓库 Settings → Secrets and variables → Actions → Variables
        新建变量 VOTE_MERGE_DELETE_FORK_BRANCH，值填 1
    本仓的变量不存在时 ${{ vars.X }} 求值为空串，即默认关闭。
    """
    try:
        if not head:
            return "无 head 信息，跳过"
        repo = ((head.get("repo") or {}).get("full_name")) or ""
        ref = head.get("ref") or ""
        if not repo or not ref:
            return "缺少 repo/ref 信息，跳过"
        if repo == base_repo:
            return "%s 属本仓，交给仓库的自动删分支设置" % ref
        if not delete_fork_head_branch_enabled():
            return ("%s 在贡献者 fork（%s），按默认设置不删；"
                    "确需清理请让维护者设置仓库变量 "
                    "VOTE_MERGE_DELETE_FORK_BRANCH=1" % (ref, repo))
        api("/repos/%s/git/refs/heads/%s" % (repo, urllib.parse.quote(ref)),
            token, method="DELETE")
        return "已删除 %s:%s" % (repo, ref)
    except urllib.error.HTTPError as e:
        return "删除 %s:%s 失败（HTTP %s，不影响合并）" % (repo, ref, e.code)
    except Exception as e:                       # 含 URLError / ValueError / KeyError
        return "清理异常（不影响合并）: %s: %s" % (type(e).__name__, e)


def report(msg):
    print(RESULT_PREFIX + msg)


# main() 内的诊断行缓冲：p() 既打印（主记录，仍是日志）又缓冲一份给 Step Summary。
# 为什么不直接把 print 重定向到 Tee：主记录必须无条件落到 stdout —— 一旦有人改了
# 缓冲的开关或初始化顺序，日志会静默变空，而日志是唯一权威记录。
_SUM = []


def p(*args):
    line = " ".join(str(a) for a in args)
    print(line)
    _SUM.append(line)


def summary(lines):
    """把诊断抄一份到 $GITHUB_STEP_SUMMARY（rt334 评审建议 3）。

    为什么值得单独做一份：闸门的详细结论只 print 到 run 日志里，而日志要逐层
    展开才能看到；checks 页面上能看到的是 Step Summary。两者对「维护者能不能
    一眼定位到是谁的哪一张票在挡着合并」差别很大 —— 这正是本 PR 声称要解决的
    那类「维护者还得自己去翻 review 列表」的摩擦。

    三条约束：
      · 【零权限成本】不调任何 API，纯本地写文件。
      · 【写不进去不算失败】summary 是副本，日志才是主记录。Steps Summary 写失败
        （环境变量不存在、磁盘只读、被别的 step 截断）时必须静默跳过，不能让
        一次「诊断输出失败」把合并闸门判成失败。
      · 【绝不吞异常类型以外的失败】这里只 catch OSError 就够：真正要防的是
        写不进文件，其余（拼写错误等）应该照常炸出来。
    """
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    try:
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    except OSError as e:
        print("- （Step Summary 写入跳过：%s；不影响判定）" % e)


def _review_ref(entry):
    """把一条计票依据格式成一句人话：谁的哪一张 review、什么时候、什么状态。

    用于「谁在挡着合并」这类诊断（rt334 评审建议）。**没有它的话**，输出只有
    「未合并（赞成率 66.7% < 门槛 100%）」，维护者必须自己去翻 review 列表
    才能定位到具体是谁的哪一票 —— 而那恰恰是「为什么合不掉」的答案。
    """
    if not entry:
        return "（无记录）"
    vote, _w, state, sub, rid = entry
    if state == AUTHOR_SYNTH_STATE:
        return "作者自投（action 无条件合成的 +1，不受时间过滤影响）"
    return "%s，review #%s，提交于 %s" % (state or "?", rid or "?", sub or "无")


def main(now=None):
    now = now or datetime.now(timezone.utc)
    token, repo, pr = (os.environ.get(k) for k in ("GH_TOKEN", "REPO", "PR"))
    if not (token and repo and pr):
        sys.exit("缺少 GH_TOKEN / REPO / PR 环境变量（无法判定，fail-closed）")

    # 两个配置文件的加载错误统一在这里收口：缺文件 / 非 UTF-8 两种，
    # 都换成能指名文件与原因的诊断行，而不是裸 traceback。
    #
    # 【为什么必须在这里兜，而不是各自就地 try】
    # ① 顺序陷阱：.voting.yml 先加载、.voters.yml 后加载。若只给后者加处理
    #    （robin 二轮 L2 当时的做法），那么「两个都缺」时崩在 .voting.yml 上，
    #    L2 那条修复**根本走不到** —— 一道为「配置缺失」写的防线，被另一份
    #    同样会缺失的配置挡在后面，看着存在、实际不可达。
    # ② 同族漏洞：load_yaml_map 对非 UTF-8 抛的是 RuntimeError（不是
    #    FileNotFoundError），就地 try FileNotFoundError 抓不到它。
    #    E2E 审查原话是「解码失败必须显式 fail-closed，**不能裸崩成 traceback**」，
    #    而当时实现只做到「不静默拍平」，raise 出去无人接 ⇒ 恰好违反自己引用的
    #    那条要求。实测四个场景全是裸 traceback：缺 .voting.yml /
    #    .voting.yml 非 UTF-8 / .voters.yml 非 UTF-8 / 两份都缺。
    #    本文件别处的既有范式是「就地 try + 说人话」（如 .voters.yml 非整数权重），
    #    这里保持一致。
    def _load_cfg(path, what):
        try:
            return load_yaml_map(path)
        except FileNotFoundError:
            sys.exit("无法判定：%s 缺失（%s）。"
                     "请在仓库根补上该文件后重跑；缺它会让闸门停在"
                     "「无法评估」，症状与「票不够」无法区分。"
                     % (path, what))
        except RuntimeError as e:            # load_yaml_map 的非 UTF-8 路径
            sys.exit("无法判定：%s（%s）" % (e, what))

    cfg = _load_cfg(".voting.yml", "投票门槛配置")
    # .voters.yml 缺文件必须当场失败，不静默当成「无人登记」：
    # 静默默认 {} 的症状是「所有 PR 永远停在 投票人 0 < 门槛 3」——方向上是
    # fail-closed，但与「配置写错了」的症状无法区分，排查方向完全跑偏，
    # 与本文件反复强调的「配置错误必须当场可见」相悖（robin 二轮 L2）。
    voters = _load_cfg(".voters.yml", "登记投票人名单")

    # 权重必须是整数。
    #
    # 【这道防线只挡住一半的嵌套写法——注释此前把这一点说反了】
    # load_yaml_map 是逐行平坦解析器：.voters.yml 若被写成嵌套
    # （voters:\n  rt334: 3），内层的 `rt334` 会被【拍平】进顶层。
    # 常见写法 `voters:` 后面留空时，外层键的值是空串 -> 非整数 -> 被下面这道
    # bad_weights 拦下（fail-closed）。但只要外层键碰巧带一个整数值
    # （`voters: 1` 换行 `  rt334: 3`），外层键本身通过整数检查，内层的 rt334
    # 就真的进了名单：action 侧 rt334 的值是嵌套 object，被 `typeof val ===
    # 'number'` 过滤掉 => 权重 0 => 不计入；本脚本却认他是【权重 3】的投票人。
    # 同一个真人，两侧权重不同，且本脚本更宽。
    # 正确判据是「这个文件根本不是平坦映射」，而不是「拍平后的值是不是整数」——
    # 后者只能偶然拦下一种写法。修法（拒绝缩进行 / 换用真 YAML 解析）会改变
    # fail-closed 面，属闸门语义变更，未擅自改：提案见 PR 描述 R84 节。
    bad_weights = sorted(k for k, v in voters.items() if not isinstance(v, int))
    if bad_weights:
        sys.exit("无法判定：.voters.yml 里这些项的权重不是整数 -> %s"
                 "（权重必须写整数；本文件的平坦解析器读不了嵌套写法，"
                 "action 也只认顶层整数权重。注意：这只挡住外层键留空的写法，"
                 "外层键带整数值时仍会拍平并放行内层键）" % ", ".join(bad_weights))

    # 负权重在加载处就 fail-closed 拒绝（robin 二轮 M1）。理由见 tally 里那段
    # 注释的展开：负值会让 total = for_it + against_it 小于 for_it，赞成率恒
    # 100%，而该人又因 w<=0 不计入 num_voters ⇒ 两道闸门同时失效。
    # w == 0 允许：语义就是「登记了但不参与计票」，tally 已按跳过处理。
    neg_weights = sorted(k for k, v in voters.items() if isinstance(v, int) and v < 0)
    if neg_weights:
        sys.exit("无法判定：.voters.yml 里这些项的权重为负 -> %s"
                 "（权重必须 >= 0；负权重会压低 total 使赞成率计算失真，"
                 "且该人不计入投票人数，两道闸门会同时失效）" % ", ".join(neg_weights))

    def _int_cfg(key, default):
        # 缺键/拼错键时不能【静默放宽】门槛（rt334 评审建议 3）。
        # 原实现是 int(cfg.get(key, default))，default 取 1 / 0 —— 也就是说
        # 把 minVotersRequired 拼成 minVoterRequired，门槛会从 3 人悄悄掉到 1 人；
        # 把 minVotingWindowMinutes 拼错，投票窗口直接变成 0（没有窗口）。
        # 症状是「票不够 / 窗口没过」，与「配置写错了」长得一模一样，排查方向
        # 完全跑偏。方向性也违反本文件「分歧一律往更严」：action 的 Config 构造
        # 函数三个默认值全是 0，即 action 在缺键时【完全放行】；本脚本原本还
        # 守着 percentageToApprove=100，一旦其余两个键被拼错就等于全面松闸。
        # 取【文档门槛】作为缺键兜底，而不是直接 sys.exit：后者一旦配置真有错
        # 就让所有 PR 永远合不掉（fail-closed 到「不可用」），而前者仍然按文档
        # 门槛挡着，且下面这行警告会让配置错误当场可见。
        #   ⚠️ 若维护者更想要「缺键即拒绝」，把下面的警告换成 sys.exit 即可，
        #     那是 rt334 建议里的另一个选项。
        if key not in cfg:
            p("⚠️ .voting.yml 缺键 `%s`（拼错？），按文档默认值 %d 判定。"
                  "缺键时若静默取更小的值会把闸门放宽，与「分歧一律往更严」相反。"
                  % (key, default))
        try:
            return int(cfg.get(key, default))
        except (TypeError, ValueError):
            sys.exit("无法判定：.voting.yml 的 %s 不是整数 -> %r"
                     "（会抛异常而非给出判定，按 fail-closed 处理）"
                     % (key, cfg.get(key)))

    need_pct = _int_cfg("percentageToApprove", 100)
    need_voters = _int_cfg("minVotersRequired", 3)
    window_min = _int_cfg("minVotingWindowMinutes", 10)

    data = api("/repos/%s/pulls/%s" % (repo, pr), token)
    head = data.get("head") or {}
    if data.get("state") != "open":
        report("未合并（PR 状态为 %s）" % data.get("state"))
        p("- PR #%s 当前状态为 `%s`（非 open），本闸门不评估也不合并。"
          % (pr, data.get("state")))
        summary(["## 自动合并复核：未合并（PR 非 open）", ""] + _SUM)
        return
    if data.get("draft"):
        report("未合并（PR 是草稿）")
        p("- PR #%s 是草稿（draft）。草稿期不接受评审投票，等它转 ready 后重跑。"
          % pr)
        summary(["## 自动合并复核：未合并（PR 是草稿）", ""] + _SUM)
        return
    author = (data.get("user") or {}).get("login")

    # 「PR 最近一次更新」= 头提交的时间。取不到就让脚本失败（合并闸门应 fail-closed）。
    sha = head.get("sha")
    if not sha:
        sys.exit("取不到 head.sha，无法确定 PR 最近一次更新，按 fail-closed 处理，不合并")
    head_commit = api("/repos/%s/commits/%s" % (repo, sha), token)
    update_time = parse_ts((head_commit.get("commit") or {}).get("committer", {}).get("date"))
    if update_time is None:
        sys.exit("无法确定 PR 最近一次更新时间，按 fail-closed 处理，不合并")

    reviews, page = [], 1
    while True:
        batch = api("/repos/%s/pulls/%s/reviews?per_page=100&page=%d" % (repo, pr, page), token)
        if not batch:
            break
        reviews.extend(batch)
        if len(batch) < 100:
            break
        page += 1

    num_voters, for_it, against_it, per_user, dropped, per_vote, dropped_detail = tally(
        reviews, voters, author, update_time, now, head_sha=sha)
    total = for_it + against_it
    pct = (for_it / total * 100) if total else 0.0
    # 数据一致性守卫（E2E 审查建议）：for_it/against_it 与 total 理应对得上，
    # 不一致说明计票逻辑出了 bug —— 显式报警而不是让「>100%」这种怪值悄悄过去。
    if for_it > total or against_it > total:
        p("⚠️ 数据异常: 赞成/反对票数与总数不一致（for=%d against=%d total=%d）"
              % (for_it, against_it, total))

    p("### 自动合并复核结果")
    p("")
    p("- PR #%s（作者 `%s`，状态 %s）" % (pr, author, data.get("state")))
    p("- 复核的头提交: `%s`" % sha)
    p("- 本脚本的窗口起点（头提交时间）: %s" % update_time.isoformat())
    # action 的窗口起点是 head.repo.pushed_at —— 头仓【任意分支】的最后 push，
    # 与本 PR 无关，而且是【仓级】的：同一 fork 上任何一次 push 都会同时推进
    # 该 fork【所有】PR 的窗口起点（实测 PR #67 与同 fork 的 #68 共用同一个
    # pushed_at）。实测 PR #67 的偏差已从 101 分钟涨到 753 分钟，且涨的那一段
    # 不是「同一次 push 换了个时刻」，而是锚点被一次次无关的 push 累加着往后
    # 推。窗口起点错 → 投票窗口被无谓拉长；全仓又没有任何 schedule，被拉长之后
    # 没有任何事件会重新评估 → PR 静默卡住。推论：对 10 分钟内一直在 push 的
    # 活跃 fork，窗口起点被持续推进 ⇒ 窗口永不合拢（活性死锁）。把差值打出来
    # 便于定位。
    action_start = parse_ts((head.get("repo") or {}).get("pushed_at"))
    if action_start:
        p("- action 的窗口起点（head.repo.pushed_at）: %s" % action_start.isoformat())
        if action_start != update_time:
            p("  ⚠️ 两者相差 %+.0f 分钟 —— action 把「头仓任意分支的最后 push」"
                  "当成了本 PR 的更新时间（仓级锚点，同 fork 的其它 PR 共用它）。"
                  "窗口起点被拉长 %.0f 分钟。"
                  % (((action_start - update_time).total_seconds() / 60),
                     max(0.0, (action_start - update_time).total_seconds() / 60)))
    p("- 登记投票人: %s" % (", ".join(sorted(voters)) or "无"))
    p("- 计票: numVoters=%d 赞成=%d 反对=%d 赞成率=%.1f%%"
          % (num_voters, for_it, against_it, pct))
    p("- 门槛: 赞成率>=%d%% 最少投票人>=%d 窗口>=%d 分钟"
          % (need_pct, need_voters, window_min))
    if dropped:
        p("- 因早于最近一次更新而作废的票: %s" % ", ".join(sorted(dropped)))
    p("")
    p("| 投票人 | 立场 | 权重 | 依据的 review |")
    p("| --- | --- | --- | --- |")
    # 表的取名范围 = 计入 numVoters 的人 + 作者（作者可能权重 0，不进 per_user，
    # 但读者要能一眼看到「作者那张是 action 替他投的合成票」）。
    # 用 if 而不是无条件补一行：无条件补会在作者已登记时打印成两行。
    rows = sorted(set(per_user) | {author} if author else set(per_user))
    for login in rows:
        vote, w = per_user.get(login) or per_vote.get(login)[:2]
        p("| `%s` | %s | %d | %s |"
              % (login, "赞成" if vote > 0 else "反对", w,
                 _review_ref(per_vote.get(login))))

    reasons = []
    if num_voters < need_voters:
        reasons.append("投票人 %d < 门槛 %d" % (num_voters, need_voters))
    if total == 0:
        reasons.append("无任何有效票（action 在此情形会因 0/0=NaN 而静默放行）")
    elif for_it * 100 < need_pct * total:
        reasons.append("赞成率 %.1f%% < 门槛 %d%%" % (pct, need_pct))
    if update_time > now:
        # 头提交时间取自【提交者的本地时钟】，可能被顶到未来（机器时钟不准、或者
        # 提交日期本身就写了未来）。此时每张票都早于 update_time → 全部作废，且
        # 窗口终点也在未来 → 永远合不上。不写出来的话，日志里只看得见
        # 「无任何有效票」，无从定位真正的原因。
        reasons.append("头提交时间 %s 比当前时间还晚 %.0f 分钟（提交者时钟异常或提交"
                       "日期在未来），所有票都会被判为早于本次更新"
                       % (update_time.isoformat(),
                          (update_time - now).total_seconds() / 60))
    if window_min > 0:
        end = update_time + timedelta(minutes=window_min)
        if end > now:
            reasons.append("投票窗口未满（%s 之后才能判定）" % end.isoformat())
    if reasons:
        p("")
        p("不合并：" + "；".join(reasons))
        report("未合并（%s）" % "；".join(reasons))
        # 「为什么合不掉」的答案必须在这里，而不是让维护者去翻 review 列表
        # （rt334 评审建议）。下面每一条都对准上面 reasons 里的某一项，
        # 逐条指名【是谁的哪一张 review】在挡着，以及还差谁。
        p("")
        p("- 阻塞明细:")
        if num_voters < need_voters:
            missing = [k for k in sorted(voters) if k not in per_user]
            p("  · 人数: 还差 %d 名登记投票人（门槛 %d，现有 %d）。未计入的登记投票人: %s"
                  % (need_voters - num_voters, need_voters, num_voters,
                     ", ".join("`%s`%s" % (k, "（票已作废）" if k in dropped_detail else "")
                               for k in missing) or "无"))
            if dropped_detail:
                for k in sorted(dropped_detail):
                    state, sub, rid = dropped_detail[k]
                    p("    · `%s` 的 %s（review #%s，%s）未落在当前头提交上（或早于本次更新），已作废"
                          % (k, state, rid, sub))
        if total == 0:
            p("  · 票数: 没有任何有效票。登记投票人里没有任何人投出决定性立场"
                  "（Approve / Request changes），或全都未落在当前头提交上（或早于本次更新）被作废。")
        elif pct < need_pct:
            blockers = sorted(k for k, v in per_vote.items() if v[0] < 0)
            p("  · 赞成率: 反对票来自 %s"
                  % ("、".join("`%s`（%s，权重 %d）"
                               % (k, per_vote[k][2], per_vote[k][1]) for k in blockers)
                     or "（无登记投票人投反对票 —— 说明反对者未登记或权重为 0）"))
            p("    要放行只能由他重新 review 或 dismiss 那张票；本闸门无过期机制。")
        if update_time > now:
            p("  · 时间: 头提交时间比现在还晚，见上方 reasons。")
        elif window_min > 0:
            end = update_time + timedelta(minutes=window_min)
            if end > now:
                p("  · 窗口: 还差 %.0f 分钟（窗口起点 %s，窗口 %d 分钟）"
                      % ((end - now).total_seconds() / 60, update_time.isoformat(),
                         window_min))
        # 阻塞分支出口：把同一份诊断抄进 Step Summary。必须在 return 之前 ——
        # 「为什么合不掉」恰恰是维护者最需要在 checks 页面直接看到的内容。
        summary(["## 自动合并复核：未合并", ""] + _SUM)
        return

    p("")
    p("- 阻塞明细: 无（人数 / 赞成率 / 窗口三项门槛全部满足）")
    p("")
    p("票数复核通过，执行 squash 合并（头提交已钉死为 `%s`）" % sha)
    merged, detail, code, merge_token = merge_pr(
        repo, pr, sha, token,
        pr_head_repo=(head.get("repo") or {}).get("full_name"))
    if not merged:
        # 走到这里说明本脚本自己的门槛全过了，票数判定没问题。但「没合上」的原因
        # 有四类，成因与处置完全不同，必须分流——GitHub 对 403/405 都只回一句含义
        # 模糊的 message，不分流就会把权限问题当成投票问题去查。
        #
        # code=None 单列一类：它不是「GitHub 拒绝了」，而是「请求没拿到响应」
        # （网络层失败 / 超时 / 缺凭据）。指纹行必须与 403/405/409 区分得开，
        # 否则「合并结果: 合并被拒（HTTP None）」既不是 HTTP 拒绝，也无法与真实
        # 状态码区分，grep 指纹的人会顺着错方向查（robin 二轮 M2/L3）。
        if code is None:
            report(detail or "无法判定：合并请求未拿到响应（缺凭据或网络层失败）")
        else:
            report("合并被拒（HTTP %s）：%s" % (code, detail or "未知原因"))
        if code is None:
            p("- **既不是投票问题，也不是 GitHub 明确拒绝** —— 本脚本的票数判定已全过。")
            if not merge_token and "凭据" in (detail or ""):
                p("- 直接原因：缺可用的合并凭据。跨仓 PR（头仓 ≠ 本仓）在 "
                  "`pull_request_target` 下拿到的 GITHUB_TOKEN 被 GitHub 降级为"
                  "只读，必须配 %s。请维护者在本仓 Settings → Secrets and"
                  " variables → Actions → Secrets 里新增它。" % MERGE_TOKEN_VAR)
                p("- 这种情况**没有任何合并请求发出过**，重跑不会改变结果，"
                  "必须先配好凭据。")
            else:
                p("- 直接原因：网络层失败 / 超时，请求没拿到响应。")
            p("- ⚠️ 不要直接重试合并：带 body 的 PUT 可能在服务端**已经生效**、"
              "只是响应没回来。")
            p("  先确认 PR 的 `merged` 状态再决定是否重跑 —— 重复请求会把「已生效」"
              "误当成「首次尝试」而让诊断失真。")
        elif code == 403:
            p("- **这是权限问题，不是投票问题。** 令牌缺 `contents: write`。成因：")
            p("  ① 本仓 PR 且 workflow 里 automerge job 的 `permissions` 没给")
            p("     `contents: write`（注意：job 级 permissions 会把未列出的权限置为 none）；")
            p("  ② 跨仓 PR —— 本脚本已改用 %s 去合并（见上方「合并凭据」行），"
              % MERGE_TOKEN_VAR)
            p("     若仍 403，说明这把凭据本身缺 `contents: write` 或不是本仓的令牌。")
            p("  这属部署/权限问题，改 workflow 或换凭据即可，无需重投票。")
        elif code == 409:
            p("- **头提交在复核之后被改过了**（TOCTOU 竞态）。这是脚本按设计拒绝：")
            p("  钉死的 sha 已不是当前 head，未复核的新提交不会被合入。")
            p("  这是**安全**行为，不是故障。新的 head 需要重新走一轮投票。")
        elif code == 405:
            p("- 本脚本的票数判定已全部通过，票数不是被拒原因。")
            p("- 这是合入前的约束不满足：ruleset / required check 挡住，或合并方法")
            p("  不被允许（GitHub 对此只回 `not mergeable` 一类模糊消息，极易被")
            p("  误读成「投票没过」）。优先排查：")
            p("  ① `democracy` 在本 PR【当前头提交】上是否重跑过、且【最新一条】run 是")
            p("     success —— required 判定取的是该 context 名最新一条 run 的结论，")
            p("     更早的陈旧 failure **不构成阻塞**（实测 PR #59 的 head 上带着一条")
            p("     早 12 小时的 democracy failure，PR 仍正常合入；`statusCheckRollup`")
            p("     把同名 context 的失败那条也并进去显示 FAILURE，那是【展示用的并集】，")
            p("     不等于「合不了」，别照着 rollup 判）；")
            p("  ② `democracy` 的投票窗口起点用的是 `head.repo.pushed_at`，可能被头仓")
            p("     【任意分支】的 push 顶到未来（见上文打印的偏差）；")
            p("  ③ 合并方法不被允许：本脚本请求 squash merge，若仓库未开启 squash，")
            p("     GitHub 同样回 405（原始 message 会指明方法）。确认：仓库 Merge 设置")
            p("     与 ruleset 里允许的方法集是否含 squash。")
            p("  修复办法：在本 PR 上产生一次真实事件（再提交一次 review，或 push 一次）")
            p("  让它在当前头提交上重跑。")
        else:
            p("- 未识别的拒绝码，处置方式同上：先看 HTTP %s 与原始 message。" % code)
        # Summary 小节标题必须与 report 的指纹同一口径：code=None 不是 HTTP 拒绝，
        # 标题里写「HTTP None」会与日志里的「无法判定」自相矛盾。
        summary(["## 自动合并复核：%s" % (
            "合并被拒（HTTP %s）" % code if code is not None
            else "无法判定（未拿到合并响应）"), ""] + _SUM)
        sys.exit(1)
    report("已合并 %s（头提交 `%s`）" % (detail or "", sha))
    p("- 贡献者分支清理: %s" % delete_head_branch(head, repo, merge_token))
    summary(["## 自动合并复核：已合并", ""] + _SUM)


if __name__ == "__main__":
    main()
