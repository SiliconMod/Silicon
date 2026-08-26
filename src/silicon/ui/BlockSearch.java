package silicon.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.type.Category;
import mindustry.ui.Styles;
import mindustry.ui.fragments.PlacementFragment;
import mindustry.world.Block;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static mindustry.Vars.*;

/**
 * Adds a search bar to the vanilla block select menu (PlacementFragment).
 * Features:
 * - searches every legal block (same rules as the vanilla menu) across all categories,
 *   matching the internal name, the display name, and (for Chinese names) fuzzy pinyin
 *   (full pinyin or initials, with subsequence fuzziness);
 * - remembers the last few used queries as clickable chips, so searches can be repeated.
 */
public class BlockSearch{
    private static final String resultName = "silicon-search-result";
    private static final String searchRowName = "silicon-block-search";

    //reflected private fields of PlacementFragment
    private static Field blockCatTableF, blockTableF, blockPaneF, togglerF, selectedBlocksF, menuHoverBlockF;
    private static Table lastToggler;

    private static Table searchRow, historyList;
    private static Element historyButton;
    private static TextField field;
    private static boolean searching;
    private static final Seq<Block> results = new Seq<>();

    //pinyin: BMP char -> plain pinyin, lazily loaded from /pinyin.txt
    private static String[] pinyin;
    private static final ObjectMap<Block, String[]> pinyinCache = new ObjectMap<>();

    //recent queries, persisted in settings
    private static Seq<String> history = new Seq<>();
    private static boolean historyLoaded;

    private BlockSearch(){
    }

    /** Must be called from the mod's init(). */
    public static void init(){
        try{
            blockCatTableF = PlacementFragment.class.getDeclaredField("blockCatTable");
            blockTableF = PlacementFragment.class.getDeclaredField("blockTable");
            blockPaneF = PlacementFragment.class.getDeclaredField("blockPane");
            togglerF = PlacementFragment.class.getDeclaredField("toggler");
            selectedBlocksF = PlacementFragment.class.getDeclaredField("selectedBlocks");
            menuHoverBlockF = PlacementFragment.class.getDeclaredField("menuHoverBlock");
            for(Field f : new Field[]{blockCatTableF, blockTableF, blockPaneF, togglerF, selectedBlocksF, menuHoverBlockF}){
                f.setAccessible(true);
            }
        }catch(Throwable t){
            Log.err("BlockSearch: unable to access block select internals, block search disabled.", t);
            return;
        }

        //the vanilla fragment rebuilds its whole UI on world load / block unlock — re-inject afterwards
        Events.on(EventType.WorldLoadEvent.class, e -> Core.app.post(() -> Core.app.post(BlockSearch::inject)));
        Events.on(EventType.UnlockEvent.class, e -> {
            if(e.content instanceof Block) Core.app.post(() -> Core.app.post(BlockSearch::inject));
        });

        //self-healing: keeps the search bar alive across fragment rebuilds, and re-applies
        //the filter if a vanilla rebuild overwrote the result grid while searching
        Events.run(Trigger.update, BlockSearch::update);
    }

    static PlacementFragment fragment(){
        if(ui == null || ui.hudfrag == null) return null;
        return ui.hudfrag.blockfrag;
    }

    @SuppressWarnings("unchecked")
    static <T> T getField(Field f, PlacementFragment frag){
        try{
            return (T)f.get(frag);
        }catch(Exception e){
            return null;
        }
    }

    static void update(){
        PlacementFragment frag = fragment();
        if(frag == null) return;

        //after the vanilla fragment is rebuilt (world load, unlock, or our own restore),
        //the old search row is orphaned: it still has a parent, but is no longer in the
        //stage — so check getScene() and re-inject into the fresh UI
        if(searchRow == null || searchRow.getScene() == null){
            inject();
            return;
        }

        //hot-apply the "show search history" setting: show/hide the history button right away
        if(historyButton != null && historyButton.visible != showHistoryEnabled()){
            historyButton.visible = showHistoryEnabled();
            if(!showHistoryEnabled()) closeHistory();
        }

        //a blank search box drops any stale result grid; the history list may still be
        //opened explicitly via the history button, so it is not force-closed here
        if(field != null && field.getText().trim().isEmpty()){
            if(searching) restore(); //drop any stale result grid as well
            return;
        }

        //if the vanilla grid clobbered our result grid while searching (e.g. hotkeys used after losing focus), re-apply
        if(searching && field != null){
            Table blockTable = getField(blockTableF, frag);
            if(blockTable != null && blockTable.find(resultName) == null){
                applyFilter(field.getText());
            }
        }
    }

    static void inject(){
        PlacementFragment frag = fragment();
        if(frag == null) return;

        Table blockCatTable = getField(blockCatTableF, frag);
        if(blockCatTable == null) return;

        //drop a stale search row if it is still attached to this table
        if(searchRow != null && searchRow.parent != null) searchRow.remove();
        if(blockCatTable.getChildren().size == 3 && searchRowName.equals(blockCatTable.getChildren().first().name)){
            blockCatTable.getChildren().first().remove();
        }
        if(blockCatTable.getChildren().size != 2) return;

        //search row: magnifier + text field + clear button + history button,
        //and a collapsible history list below (empty = blank, no layout gap);
        //hovering the history button shows its tooltip; clicking toggles the list.
        //the panel background (Tex.pane2) and margin match the vanilla block grid so the
        //search bar blends into the original item bar
        searchRow = new Table(Tex.pane2);
        searchRow.name = searchRowName;
        searchRow.top().left().margin(4f);
        searchRow.image(Icon.zoom).padRight(8f);
        field = searchRow.field("", BlockSearch::onChanged).growX().height(38f)
            .name("silicon-search-field").maxTextLength(64).get();
        field.setMessageText(Core.bundle.get("blocksearch.hint"));
        searchRow.button(Icon.cancel, Styles.clearNoneTogglei, BlockSearch::clearSearch).size(38f).padLeft(6f).name("silicon-search-clear");
        historyButton = searchRow.button(Icon.downOpen, Styles.clearNonei, BlockSearch::toggleHistory).size(38f).padLeft(4f)
            .name("silicon-search-history").tooltip(Core.bundle.get("blocksearch.history")).get();
        historyButton.visible = showHistoryEnabled(); //hot-applied every frame in update()
        searchRow.row();

        historyList = new Table(Tex.pane2);
        historyList.name = "silicon-search-history-list";
        historyList.top().left();
        historyList.visible = false; //hidden until the history dropdown is opened
        searchRow.add(historyList).colspan(4).growX().padTop(2f);

        //re-parent the vanilla tables so the search bar sits on top of the block grid
        Table blocksSelect = (Table)blockCatTable.getChildren().get(0);
        Table categories = (Table)blockCatTable.getChildren().get(1);
        blockCatTable.clearChildren();
        blockCatTable.add(searchRow).colspan(2).growX().row();
        blockCatTable.add(blocksSelect).fillY().bottom().touchable(Touchable.enabled);
        blockCatTable.add(categories).fillY().bottom().touchable(Touchable.enabled);

        //clicking anywhere outside the search bar releases the field's keyboard focus,
        //so the vanilla block-select hotkeys keep working right after searching
        Table toggler = getField(togglerF, frag);
        if(toggler != null && toggler != lastToggler){
            lastToggler = toggler;
            toggler.addListener(new ClickListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    Element target = Core.scene.hit(x, y, true);
                    //clicking outside the search bar closes the history list and releases the field's keyboard focus,
                    //so the vanilla block-select hotkeys keep working right after searching
                    if(target == null || !target.isDescendantOf(searchRow)){
                        closeHistory();
                        if(field != null && Core.scene.getKeyboardFocus() == field){
                            Core.scene.setKeyboardFocus(null);
                        }
                    }
                    return super.touchDown(event, x, y, pointer, button);
                }
            });
        }
    }

    static void onChanged(String text){
        if(text == null || text.trim().isEmpty()){
            restore();
            closeHistory(); //blank search box must not show the history list
        }else{
            closeHistory(); //typing hides the history list
            applyFilter(text);
        }
    }

    static void applyFilter(String text){
        PlacementFragment frag = fragment();
        if(frag == null) return;

        Table blockTable = getField(blockTableF, frag);
        if(blockTable == null) return;

        String q = text.trim().replaceAll(" +", " ").toLowerCase();
        if(q.isEmpty()){
            restore();
            return;
        }

        searching = true;

        //collect every legal block (same rules as the vanilla menu) whose name matches, across all categories
        results.clear();
        for(Block block : content.blocks()){
            if(!block.isVisible() || !unlocked(block)) continue;
            if(matches(block, q)) results.add(block);
        }
        results.sort((a, b) -> {
            int c = Integer.compare(a.category.ordinal(), b.category.ordinal());
            if(c != 0) return c;
            c = Boolean.compare(!a.isPlaceable(), !b.isPlaceable());
            if(c != 0) return c;
            return a.localizedName.compareTo(b.localizedName);
        });

        blockTable.clear();
        blockTable.top().margin(5);

        if(results.isEmpty()){
            blockTable.add(Core.bundle.get("blocksearch.noresults")).color(Color.lightGray).pad(10f).left();
            blockTable.act(0f);
            return;
        }

        int index = 0;
        ButtonGroup<ImageButton> group = new ButtonGroup<>();
        group.setMinCheckCount(0);

        for(Block block : results){
            if(index++ % 4 == 0) blockTable.row();

            ImageButton button = blockTable.button(new TextureRegionDrawable(block.uiIcon), Styles.selecti, () -> select(frag, block))
                .size(46f).group(group).name(resultName).get();
            button.resizeImage(iconMed);

            button.update(() -> { //mirrors the vanilla coloring: gray when unaffordable, dark gray when unplaceable
                Building core = player.core();
                Color color = (state.rules.infiniteResources || (core != null && (core.items.has(block.requirements, state.rules.buildCostMultiplier) || state.rules.infiniteResources))) && player.isBuilder() ? Color.white : Color.gray;
                button.forEach(elem -> elem.setColor(color));
                button.setChecked(control.input.block == block);
                if(!block.isPlaceable()){
                    button.forEach(elem -> elem.setColor(Color.darkGray));
                }
            });

            button.hovered(() -> setMenuHover(frag, block));
            button.exited(() -> {
                if(getMenuHover(frag) == block) setMenuHover(frag, null);
            });
        }
        if(index < 4){
            for(int i = 0; i < 4 - index; i++) blockTable.add().size(46f);
        }
        blockTable.act(0f);

        //start the result list at the top
        ScrollPane pane = getField(blockPaneF, frag);
        if(pane != null) pane.setScrollYForce(0f);
    }

    static boolean matches(Block block, String q){
        if(block.name.toLowerCase().contains(q)) return true;
        if(block.localizedName.toLowerCase().contains(q)) return true;

        //fuzzy pinyin matching (only meaningful for Chinese display names)
        String[] py = pinyinOf(block);
        if(py != null){
            if(py[0].contains(q) || py[1].contains(q)) return true;
            if(q.length() >= 2 && (isSubsequence(q, py[0]) || isSubsequence(q, py[1]))) return true;
        }
        return false;
    }

    /** Full pinyin (lowercase, no tones) and initials for the display name; null if it has no Chinese characters. */
    static String[] pinyinOf(Block block){
        String[] cached = pinyinCache.get(block);
        if(cached != null) return cached;

        String[] table = pinyinMap();
        if(table == null) return null;

        StringBuilder full = new StringBuilder(), init = new StringBuilder();
        for(char c : block.localizedName.toCharArray()){
            String py = table[c];
            if(py == null) continue;
            full.append(py);
            init.append(py.charAt(0));
        }
        if(full.length() == 0) return null;

        String[] result = {full.toString(), init.toString()};
        pinyinCache.put(block, result);
        return result;
    }

    static boolean isSubsequence(String q, String s){
        int i = 0;
        for(int j = 0; j < s.length() && i < q.length(); j++){
            if(s.charAt(j) == q.charAt(i)) i++;
        }
        return i == q.length();
    }

    /** Lazily loads the bundled hanzi -> pinyin table (resource /pinyin.txt, one "HEX pinyin" per line). */
    static String[] pinyinMap(){
        if(pinyin == null){
            String[] table = new String[0x10000];
            try(InputStream in = BlockSearch.class.getResourceAsStream("/pinyin.txt")){
                if(in == null){
                    Log.warn("BlockSearch: pinyin table not found, pinyin search disabled.");
                }else{
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while((line = r.readLine()) != null){
                        int sp = line.indexOf(' ');
                        if(sp <= 0) continue;
                        try{
                            int cp = Integer.parseInt(line.substring(0, sp), 16);
                            if(cp < table.length) table[cp] = line.substring(sp + 1).trim();
                        }catch(Exception ignored){
                        }
                    }
                }
            }catch(Exception e){
                Log.err("BlockSearch: failed to load pinyin table", e);
            }
            pinyin = table;
        }
        return pinyin;
    }

    /** Same rules as the vanilla menu: the block is unlocked, placeable by the player and valid in this environment. */
    static boolean unlocked(Block block){
        return block.unlockedNowHost() && block.placeablePlayer && block.environmentBuildable() &&
            block.supportsEnv(state.rules.env);
    }

    static void select(PlacementFragment frag, Block block){
        control.input.block = control.input.block == block ? null : block;
        ObjectMap<Category, Block> selected = getField(selectedBlocksF, frag);
        if(selected != null) selected.put(block.category, control.input.block);
        frag.currentCategory = block.category; //after the search clears, the grid opens on this block's category

        //remember the query that led to this pick, so the search can be repeated later
        if(field != null && !field.getText().trim().isEmpty()){
            addHistory(field.getText().trim());
        }

        //only clear the input/results when the player asked for it
        if(clearOnSelectEnabled()){
            clearSearch();
        }
    }

    static void clearSearch(){
        if(field != null) field.setText("");
        closeHistory();
        if(Core.scene != null) Core.scene.setKeyboardFocus(null);
        restore();
    }

    /** Restores the vanilla category grid by rebuilding the whole fragment (deferred to the next frame). */
    static void restore(){
        if(!searching) return;
        searching = false;
        PlacementFragment frag = fragment();
        if(frag == null) return;
        Core.app.post(() -> {
            //if a new search was applied while the rebuild was pending (e.g. a history chip
            //was clicked right away), don't wipe the fresh results
            if(!searching && ui != null && ui.hudfrag != null && ui.hudfrag.blockfrag != null){
                ui.hudfrag.blockfrag.rebuild();
            }
        });
    }

    //---- settings ----
    //whether the recent-search feature is enabled at all
    static boolean showHistoryEnabled(){
        return Core.settings.getBool("blocksearch.showHistory", true);
    }

    //whether picking a block from the results clears the search input
    static boolean clearOnSelectEnabled(){
        return Core.settings.getBool("blocksearch.clearOnSelect", true);
    }

    //---- recent search history (repeat searches) ----

    static Seq<String> history(){
        if(!historyLoaded){
            historyLoaded = true;
            try{
                history = Core.settings.getJson("blocksearch-history", Seq.class, String.class, Seq::new);
            }catch(Exception e){
                history = new Seq<>();
            }
            if(history == null) history = new Seq<>();
        }
        return history;
    }

    static void addHistory(String q){
        if(!showHistoryEnabled()) return;
        Seq<String> h = history();
        if(q.isEmpty()) return;
        h.remove(q);
        h.insert(0, q);
        while(h.size > 4) h.pop();
        try{
            Core.settings.putJson("blocksearch-history", String.class, h);
        }catch(Exception ignored){
        }
        //refresh the list if it is currently open
        if(historyList != null && historyList.getChildren().size > 0) rebuildHistoryList();
    }

    static void toggleHistory(){
        if(historyList == null) return;
        if(!showHistoryEnabled()){
            closeHistory();
            return;
        }
        if(historyList.getChildren().size > 0){
            closeHistory();
        }else{
            rebuildHistoryList();
        }
    }

    static void closeHistory(){
        if(historyList != null){
            historyList.clearChildren();
            historyList.visible = false; //no black panel strip between the search bar and the block grid
        }
    }

    /** Rebuilds the recent-search dropdown (empty list = empty table = no layout gap). */
    static void rebuildHistoryList(){
        if(historyList == null) return;
        historyList.clearChildren();
        historyList.visible = true; //the dropdown is open now
        historyList.top().left().margin(4f);

        Seq<String> h = history();
        if(h.isEmpty()){
            historyList.add(Core.bundle.get("blocksearch.nohistory")).color(Color.lightGray).pad(6f);
            return;
        }

        for(String q : h){
            String fq = q;
            historyList.button(q, Styles.flatBordert, () -> {
                if(field != null){
                    field.setText(fq);
                    //keep the keyboard on the field so typing can continue right away
                    if(Core.scene != null) Core.scene.setKeyboardFocus(field);
                }
                applyFilter(fq); //apply directly — TextField.setText does not fire the change listener
                closeHistory();
            }).growX().pad(3f);
            historyList.row();
        }
    }

    static void setMenuHover(PlacementFragment frag, Block block){
        try{
            menuHoverBlockF.set(frag, block);
        }catch(Exception ignored){
        }
    }

    static Block getMenuHover(PlacementFragment frag){
        return getField(menuHoverBlockF, frag);
    }
}
