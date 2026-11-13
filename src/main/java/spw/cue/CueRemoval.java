package spw.cue;

import androidx.compose.foundation.ContextMenuItem;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import java.awt.EventQueue;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kotlinx.coroutines.flow.StateFlow;
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

    static Set<String> selectedEntries(SongHookTarget target, StateFlow<?> pageState, Set<?> selectedKeys, SongVisibility store) {
        if (selectedKeys.isEmpty() || !(pageState.getValue() instanceof StateFlow<?> currentPage)) return Set.of();
        Object current = currentPage.getValue();
        if (current == null) return Set.of();
        List<?> rows;
        try {
            rows = target.groupEntities(current);
        } catch (IllegalStateException incompatibleState) {
            return Set.of();
        }
        if (!ProjectedRows.groups.isInstance(rows)) return Set.of();

        var entries = new HashSet<String>();
        Set<String> hidden = store.getHidden().getValue();
        try {
            for (Object group : (List<?>) ProjectedRows.entries.invoke(rows)) {
                for (Object row : (List<?>) ProjectedRows.rows.invoke(group)) {
                    if (!ProjectedRows.cue.isInstance(row)) continue;
                    Object song = ProjectedRows.song.invoke(row);
                    if (selectedKeys.contains(ProjectedRows.key.invoke(song))) {
                        String entry = (String) ProjectedRows.visibilityKey.invoke(song);
                        if (!hidden.contains(entry)) entries.add(entry);
                    }
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法读取所选 CUE 分轨", error);
        }
        return Set.copyOf(entries);
    }

    private static final class ProjectedRows {
        private static final Class<?> groups = type("SongGroups");
        private static final Class<?> cue = type("RowPlan$Cue");
        private static final java.lang.reflect.Method entries = getter(groups, "getEntries");
        private static final java.lang.reflect.Method rows = getter(type("GroupPlan"), "getRows");
        private static final java.lang.reflect.Method song = getter(cue, "getSong");
        private static final java.lang.reflect.Method key = getter(type("Song"), "getKey");
        private static final java.lang.reflect.Method visibilityKey = getter(type("Song"), "getVisibilityKey");

        private static Class<?> type(String name) {
            try {
                return Class.forName(NativeSongCue.class.getName() + "$" + name, false, NativeSongCue.class.getClassLoader());
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException(error);
            }
        }

        private static java.lang.reflect.Method getter(Class<?> owner, String name) {
            try {
                return owner.getMethod(name);
            } catch (NoSuchMethodException error) {
                throw new IllegalStateException(error);
            }
        }
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
