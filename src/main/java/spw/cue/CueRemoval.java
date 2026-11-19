package spw.cue;

import androidx.compose.foundation.ContextMenuItem;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import java.awt.EventQueue;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

final class CueRemoval {
    private static volatile SongVisibility currentStore;

    static void bind(SongVisibility store) {
        currentStore = store;
    }

    static MutableState<Set<String>> albumState(Set<String> removed) {
        return SnapshotStateKt.mutableStateOf(removed, SnapshotStateKt.structuralEqualityPolicy());
    }

    static void publishAlbum(MutableState<Set<String>> state, Set<String> removed) {
        EventQueue.invokeLater(() -> state.setValue(removed));
    }

    static AlbumCue visibleAlbum(AlbumCue album) {
        SongVisibility store = currentStore;
        if (store == null) return album;
        Set<String> removed = store.getAlbumRemoved().getValue();
        if (removed.isEmpty()) return album;
        var tracks = album.getTracks();
        var visible = tracks.stream().filter(track -> !removed.contains(SongVisibility.Companion.key(track))).toList();
        return visible.size() == tracks.size() ? album : new AlbumCue(visible, album.getDurationMs());
    }

    static Set<String> selectedEntries(Map<String, String> visibleEntries, Set<?> selectedKeys, SongVisibility store) {
        var entries = new HashSet<String>();
        Set<String> hidden = store.getHidden().getValue();
        for (Object selected : selectedKeys) {
            String entry = visibleEntries.get(selected);
            if (entry != null && !hidden.contains(entry)) entries.add(entry);
        }
        return Set.copyOf(entries);
    }

    static Function0<List<ContextMenuItem>> menu(
        Function0<Unit> hide, Function0<Unit> remove, Function0<Set<String>> selected, Function1<Set<String>, Unit> removeSelected,
        Function1<Set<String>, Unit> hideSelected
    ) {
        return () -> {
            Set<String> entries = selected.invoke();
            int count = Math.max(1, entries.size());
            Function0<Unit> removeAction = entries.isEmpty() ? remove : () -> removeSelected.invoke(entries);
            Function0<Unit> hideAction = entries.isEmpty() ? hide : () -> hideSelected.invoke(entries);
            return List.of(
                new ContextMenuItem("从曲库移除所选分轨（" + count + "）", removeAction),
                new ContextMenuItem("从歌曲页隐藏所选分轨（" + count + "）", hideAction)
            );
        };
    }
}
