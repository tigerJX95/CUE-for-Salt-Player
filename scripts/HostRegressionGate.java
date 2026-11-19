import java.lang.reflect.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Run with JDK 25 and an unmodified local Salt Player host archive. No native playback is started. */
public class HostRegressionGate {
    interface Action { Object invoke(Object[] arguments) throws Exception; }
    static ClassLoader loader;

    static final class TaskbarSink {
        final List<List<Object>> updates=new ArrayList<>();
        void update(boolean playing,long elapsed,long duration) {
            updates.add(List.of(playing,elapsed,duration));
        }
    }

    static Object hookRegistrar(Path folder) throws Exception {
        Class<?> frame=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Class<?> registrarType=frame.getConstructors()[0].getParameterTypes()[0];
        Constructor<?> registrar=registrarType.getConstructors()[0];
        Class<?> managerType=registrar.getParameterTypes()[0];
        Object manager=managerType.getConstructor(Path[].class).newInstance((Object)new Path[]{folder});
        return registrar.newInstance(manager);
    }

    static void taskbarProgressThroughHostFrame(Path folder,List<?> tracks) throws Exception {
        Class<?> frameType=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Constructor<?> frame=frameType.getConstructors()[0];
        Class<?>[] parameters=frame.getParameterTypes();
        Object hooks=hookRegistrar(folder);
        Method original=TaskbarSink.class.getDeclaredMethod("update",boolean.class,long.class,long.class);
        MethodHandle invokeOriginal=MethodHandles.lookup().unreflect(original);
        Object site=parameters[1].getConstructor(int.class,Method.class,MethodHandle.class)
            .newInstance(1,original,invokeOriginal);
        Object taskbar=singleton("spw.cue.HostTaskbarProgress");
        Method update=taskbar.getClass().getMethod("update",type("com.xuncorp.spw.workshop.api.hook.MethodHookParam"),List.class);
        Method isSkipped=frameType.getMethod("isOriginalSkipped");
        TaskbarSink sink=new TaskbarSink();
        Object nextTrack=frame.newInstance(hooks,site,sink,new Object[]{true,10_000L,30_000L});
        Object paused=frame.newInstance(hooks,site,sink,new Object[]{false,17_000L,30_000L});
        Object regular=frame.newInstance(hooks,site,sink,new Object[]{true,4_000L,30_000L});
        require(update.invoke(taskbar,nextTrack,tracks).equals(true) && isSkipped.invoke(nextTrack).equals(true),
            "The native taskbar must be called once with the replacement arguments");
        require(update.invoke(taskbar,paused,tracks).equals(true) && isSkipped.invoke(paused).equals(true),
            "Paused CUE progress must be relative to the track");
        require(update.invoke(taskbar,regular,List.of()).equals(false) && isSkipped.invoke(regular).equals(false),
            "Regular songs must use the host's standard taskbar progress");
        require(sink.updates.equals(List.of(List.of(true,0L,20_000L),List.of(false,7_000L,20_000L))),
            "The real hook frame did not forward CUE progress to the native method");
        System.out.println("PASS Windows taskbar updates through the real host hook frame");
    }

    static void verifyArtistGetterRegistration(Object catalog,Method getter) throws Exception {
        Class<?> frame=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Class<?> registrar=frame.getConstructors()[0].getParameterTypes()[0];
        Method registration=registrar.getMethod("hookMethod",String.class,String.class,List.class,
            type("com.xuncorp.spw.workshop.api.hook.MethodHook"));
        List<?> calls=(List<?>)catalog.getClass().getMethod("calls",Method.class).invoke(catalog,registration);
        InvokeInstruction resolution=calls.stream().map(InvokeInstruction.class::cast).filter(call ->
            call.type().stringValue().startsWith("(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;)Lcom/xuncorp/voxzen/workshop/hooks/"))
            .findFirst().orElseThrow();
        Class<?> resolver=type(resolution.owner().asInternalName().replace('/','.'));
        Object receiver=Arrays.stream(resolver.getFields()).filter(f->Modifier.isStatic(f.getModifiers()) && f.getType()==resolver)
            .findFirst().orElseThrow().get(null);
        Object site=resolver.getMethod(resolution.name().stringValue(),String.class,String.class,List.class)
            .invoke(receiver,getter.getDeclaringClass().getName(),getter.getName(),List.of());
        Method resolved=(Method)Arrays.stream(site.getClass().getMethods()).filter(m->m.getReturnType()==Method.class && m.getParameterCount()==0)
            .findFirst().orElseThrow().invoke(site);
        require(resolved.equals(getter),"Salt Player's actual MethodHook resolver must accept the Artist state getter");
        System.out.println("PASS Artist Songs state getter accepted by Salt Player's actual Hook resolver");
    }

    static void verifyArtistListRows(Object hooks,Object target,Object page,Object groups,int expected) throws Exception {
        Class<?> frameType=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Constructor<?> frame=frameType.getConstructors()[0];
        Method builder=(Method)target.getClass().getMethod("getListBuilder").invoke(target);
        Method batch=(Method)target.getClass().getMethod("getItemBatch").invoke(target);
        MethodHandle build=MethodHandles.privateLookupIn(builder.getDeclaringClass(),MethodHandles.lookup()).unreflect(builder);
        Object site=frame.getParameterTypes()[1].getConstructor(int.class,Method.class,MethodHandle.class)
            .newInstance(2,builder,build);
        Class<?>[] types=builder.getParameterTypes();
        Class<?> scope=types[types.length-1];
        List<Integer> sizes=new ArrayList<>();
        Object[] args=new Object[types.length];
        for(int i=0;i<args.length;i++){
            if(types[i]==long.class)args[i]=0L;
            if(types[i]==int.class)args[i]=0;
            if(types[i]==boolean.class)args[i]=false;
        }
        args[0]=groups;
        args[args.length-1]=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{scope},(proxy,method,values)->{
            if(method.equals(batch))sizes.add((Integer)values[0]);
            return null;
        });
        Object call=frame.newInstance(hooks,site,null,args);
        page.getClass().getMethod("beforeList",type("com.xuncorp.spw.workshop.api.hook.MethodHookParam")).invoke(page,call);
        require(frameType.getMethod("isOriginalSkipped").invoke(call).equals(true) &&
            sizes.stream().mapToInt(Integer::intValue).sum()==expected,
            "The real artist list builder must submit " + expected + " track rows. Batches: " + sizes);
    }

    static void artistStateThroughHostFrame(Path folder,Object binding,Object target,Object page,List<?> originals,Object cueSource) throws Exception {
        Constructor<?> success=(Constructor<?>)field(binding,"success");
        Object artist=success.getParameterTypes()[0].getConstructors()[0]
            .newInstance("Artist",false,"",0L,0,"","",true,false);
        List<?> albums=List.of();
        Object original=success.newInstance(artist,originals,false,2,albums,false);
        Class<?> flow=type("kotlinx.coroutines.flow.StateFlow");
        Object mutable=type("kotlinx.coroutines.flow.StateFlowKt").getMethod("MutableStateFlow",Object.class).invoke(null,original);
        Class<?> flowKt=type("kotlinx.coroutines.flow.FlowKt");
        Object source=flowKt.getMethod("asStateFlow",type("kotlinx.coroutines.flow.MutableStateFlow")).invoke(null,mutable);
        Method flowOf=flowKt.getMethod("flowOf",Object.class);
        Class<?> type=type("spw.cue.NativeArtistSongs");
        Method project=page.getClass().getMethod("projectArtistGroups",List.class,boolean.class,Set.class);
        Object hook=type.getConstructors()[0].newInstance(binding,function("kotlin.jvm.functions.Function0",true),
            flowOf.invoke(null,true),function("kotlin.jvm.functions.Function0",true),
            flowOf.invoke(null,Set.of()),flowOf.invoke(null,0L),
            callback("kotlin.jvm.functions.Function3",a->project.invoke(page,a)),function("kotlin.jvm.functions.Function1",null));

        Method getter=(Method)binding.getClass().getMethod("getStateGetter").invoke(binding);
        Class<?> frameType=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Constructor<?> frame=frameType.getConstructors()[0];
        MethodHandle originalGetter=MethodHandles.privateLookupIn(getter.getDeclaringClass(),MethodHandles.lookup()).unreflect(getter);
        Object site=frame.getParameterTypes()[1].getConstructor(int.class,Method.class,MethodHandle.class)
            .newInstance(1,getter,originalGetter);
        Object hooks=hookRegistrar(folder);
        Object call=frame.newInstance(hooks,site,null,new Object[0]);
        Method complete=Arrays.stream(frameType.getMethods()).filter(m->m.getName().startsWith("complete$")).findFirst().orElseThrow();
        complete.invoke(call,source,null);
        type.getMethod("afterRead",type("com.xuncorp.spw.workshop.api.hook.MethodHookParam")).invoke(hook,call);
        require(frameType.getMethod("getResult").invoke(call)==source,"Artist hook must leave the real getter result intact");

        Method groups=binding.getClass().getMethod("groups",Object.class);
        CompletableFuture<Object> completion=new CompletableFuture<>();
        Object context=singleton("kotlin.coroutines.EmptyCoroutineContext");
        Class<?> continuation=type("kotlin.coroutines.Continuation");
        Object resume=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{continuation},(proxy,method,args)->{
            if (method.getName().equals("getContext")) return context;
            if (method.getName().equals("resumeWith")) {
                try {
                    type("kotlin.ResultKt").getMethod("throwOnFailure",Object.class).invoke(null,args[0]);
                    completion.complete(args[0]);
                } catch (InvocationTargetException error) {
                    completion.completeExceptionally(error.getCause());
                }
            }
            return null;
        });
        Object predicate=callback("kotlin.jvm.functions.Function2",a -> groups.invoke(binding,a[0])!=originals);
        Method first=flowKt.getMethod("first",type("kotlinx.coroutines.flow.Flow"),
            type("kotlin.jvm.functions.Function2"),continuation);
        Object mapped;
        try {
            // 1.19.3 always returns the original object even if its Hook frame result changes.
            mapped=first.invoke(null,source,predicate,resume);
            if (!success.getDeclaringClass().isInstance(mapped)) mapped=completion.get(8,TimeUnit.SECONDS);
        } finally {
            ((AutoCloseable)hook).close();
        }
        Object projected=groups.invoke(binding,mapped);
        require(displayed(target,projected).size()==3,"Original Artist StateFlow did not emit two CUE tracks and the regular track");
        List<?> accessors=(List<?>)field(binding,"accessors");
        require(((Method)accessors.get(3)).invoke(mapped).equals(3),"Artist Songs count must include the visible CUE tracks");
        require(((Method)accessors.get(4)).invoke(mapped)==albums && flow.getMethod("getValue").invoke(source)==original,
            "Artist albums must remain intact and closing the hook must restore the original view-model state");
        verifyArtistListRows(hooks,target,page,projected,3);
        Object cueOnly=target.getClass().getMethod("replaceTracks",Object.class,List.class)
            .invoke(target,originals.getFirst(),List.of(cueSource));
        verifyArtistListRows(hooks,target,page,field(project.invoke(page,List.of(cueOnly),true,Set.of()),"groups"),2);
        System.out.println("PASS Original Artist StateFlow and native list builder submit CUE-only and mixed artist rows");
    }

    static Class<?> type(String name) throws Exception { return Class.forName(name, false, loader); }
    static Object singleton(String name) throws Exception { return type(name).getField("INSTANCE").get(null); }
    static Object resolve(String name) throws Exception {
        Object c = type(name).getField("Companion").get(null);
        return c.getClass().getMethod("resolve").invoke(c);
    }
    static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static Object function(String name, Object value) throws Exception {
        return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{type(name)}, (p, m, args) -> {
            if(m.getName().equals("hashCode"))return System.identityHashCode(p);
            if(m.getName().equals("equals"))return p == args[0];
            if(m.getName().equals("toString"))return "regression-gate";
            return value;
        });
    }
    static Object callback(String name, Action action) throws Exception {
        return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{type(name)}, (p,m,args) -> {
            if(m.getName().equals("hashCode"))return System.identityHashCode(p);
            if(m.getName().equals("equals"))return p==args[0];
            if(m.getName().equals("toString"))return "regression-action";
            return action.invoke(args);
        });
    }
    static Object records(Object store) throws Exception {
        Object flow=store.getClass().getMethod("getRecovery").invoke(store);
        return type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(flow);
    }
    static List<?> rows(Object target, List<?> groups) throws Exception {
        List<Object> result = new ArrayList<>();
        Method tracks = target.getClass().getMethod("tracksIn",Object.class);
        for(Object group:groups)result.addAll((List<?>)tracks.invoke(target,group));
        return result;
    }
    static List<?> displayed(Object target, Object projection) throws Exception {
        return rows(target,(List<?>)field(projection,"selectableGroups"));
    }
    static Object track(Path path, String id) throws Exception {
        Constructor<?> ctor = Arrays.stream(type("com.xuncorp.voxzen.data.entity.Track").getConstructors())
            .filter(c -> c.getParameterCount() == 23).findFirst().orElseThrow();
        Class<?>[] types = ctor.getParameterTypes();
        Object[] values = new Object[types.length];
        for(int i=0;i<types.length;i++) {
            values[i]=types[i] == String.class ? "" : types[i] == int.class ? 0 : types[i] == long.class ? 0L :
                types[i] == float.class ? 0f : types[i] == boolean.class ? false : null;
        }
        values[0]=id;values[1]="Whole album";values[2]="Artist";values[3]="Album";values[4]="Artist";
        values[11]=path.toString();values[12]=true;values[17]=30_000L;
        values[21]=type("com.xuncorp.voxzen.data.entity.ReplayGain").getConstructor().newInstance();
        return ctor.newInstance(values);
    }
    public static void main(String[] args) throws Exception {
        require(args.length==2,"Usage: HostRegressionGate.java PLUGIN_JAR HOST_ARCHIVE");
        Path plugin=Path.of(args[0]).toAbsolutePath(), host=Path.of(args[1]).toAbsolutePath();
        require(Files.isRegularFile(plugin) && Files.isRegularFile(host),"Plugin and host archive must exist");
        try(URLClassLoader isolated=new URLClassLoader(new URL[]{plugin.toUri().toURL(),host.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            loader=isolated;
            Object catalog=singleton("spw.cue.HostCatalog");
            catalog.getClass().getMethod("prepare").invoke(catalog);
            Object album=resolve("spw.cue.AlbumHookTarget");
            require(album.getClass().getMethod("getCover").invoke(album)!=null,"Album cover binding missing");
            require(album.getClass().getMethod("getPlayback").invoke(album)!=null,"Album playback binding missing");
            Object cover=album.getClass().getMethod("getCover").invoke(album);
            Method image=(Method)cover.getClass().getMethod("getRender").invoke(cover);
            for(String name:List.of("gridCaller","artistCaller","songCaller")) {
                String caller=(String)field(cover,name);
                require(caller!=null,"Missing cover caller: "+name);
                catalog.getClass().getMethod("requireSingleCall",Class.class,Method.class).invoke(catalog,type(caller),image);
            }
            require(field(cover,"platformPath")!=null,"Cover audio-path conversion missing");
            Object song=resolve("spw.cue.SongHookTarget");
            Method nativeList=(Method)song.getClass().getMethod("getNativeList").invoke(song);
            Object companion=type("spw.cue.ArtistSongTarget").getField("Companion").get(null);
            Object artist=companion.getClass().getMethod("resolve",Method.class).invoke(companion,nativeList);
            require(artist!=null,"Artist Songs binding missing");
            verifyArtistGetterRegistration(catalog,(Method)artist.getClass().getMethod("getStateGetter").invoke(artist));
            List<?> taskbarMarkers=(List<?>)catalog.getClass().getMethod("candidates",String.class)
                .invoke(catalog,"Failed to update taskbar progress");
            if (host.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dll") || !taskbarMarkers.isEmpty()) {
                Object taskbar=singleton("spw.cue.HostTaskbarProgress");
                Method progress=(Method)taskbar.getClass().getMethod("resolve").invoke(taskbar);
                require(Arrays.equals(progress.getParameterTypes(),new Class<?>[]{boolean.class,long.class,long.class}),
                    "Windows taskbar playback progress binding missing");
                System.out.println("PASS Windows taskbar progress binding");
            }
            song.getClass().getMethod("probeFilter").invoke(song);
            song.getClass().getMethod("probeList").invoke(song);
            System.out.println("PASS host bindings: main Songs, Artist Songs, Album Details, playback");

            Path folder=Files.createTempDirectory("cue-host-regression-");
            try {
                Path audio=Files.write(folder.resolve("album.wav"),new byte[]{0});
                Path ordinary=Files.write(folder.resolve("ordinary.wav"),new byte[]{0});
                Path cue=Files.writeString(folder.resolve("album.cue"),
                    "FILE album.wav WAVE\nTRACK 01 AUDIO\nTITLE First\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE Second\nINDEX 01 00:10:00\n");
                if (!taskbarMarkers.isEmpty()) {
                    Object parser=singleton("spw.cue.CueParser");
                    Object sheet=parser.getClass().getMethod("read",Path.class).invoke(parser,cue);
                    List<?> cueTracks=(List<?>)sheet.getClass().getMethod("getTracks").invoke(sheet);
                    taskbarProgressThroughHostFrame(Files.createDirectory(folder.resolve("host-plugins")),cueTracks);
                }
                Object source=track(audio,"source"), normal=track(ordinary,"normal");
                Constructor<?> group=(Constructor<?>)field(song,"group");
                List<?> originals=List.of(group.newInstance("A",List.of(source,normal)));
                Class<?> pageType=type("spw.cue.NativeSongCue");
                Constructor<?> ctor=Arrays.stream(pageType.getConstructors()).filter(c -> c.getParameterCount()==6).findFirst().orElseThrow();
                Object diagnostics=type("spw.cue.SongPageDiagnostics").getConstructor().newInstance();
                Object page=ctor.newInstance(song,function("kotlin.jvm.functions.Function0",false),
                    function("kotlin.jvm.functions.Function0",true),diagnostics,null,function("kotlin.jvm.functions.Function2",null));
                Constructor<?> songState=(Constructor<?>)field(song,"songState");
                Object sort=songState.getParameterTypes()[3].getEnumConstants()[0];
                Object grouped=song.getClass().getMethod("filterTracks",List.class,Object.class,boolean.class,String.class)
                    .invoke(song,List.of(source,normal),sort,false,"");
                List<?> artistGroups=(List<?>)song.getClass().getMethod("groupEntities",Object.class).invoke(song,grouped);
                artistStateThroughHostFrame(Files.createDirectory(folder.resolve("artist-plugins")),artist,song,page,artistGroups,source);
                Method project=pageType.getMethod("projectArtistGroups",List.class,boolean.class,Set.class);
                Object expanded=field(project.invoke(page,originals,false,Set.of()),"groups");
                require(displayed(song,expanded).size()==4,"Artist Songs must retain whole audio and show both CUE tracks plus ordinary song");
                Object hidden=field(project.invoke(page,originals,true,Set.of()),"groups");
                require(Boolean.TRUE.equals(field(hidden,"artistPage")),"Artist rows must render without first mounting main Songs");
                List<?> tracks=displayed(song,hidden);
                require(tracks.size()==3,"Hiding whole audio must retain two CUE tracks and ordinary song");
                Method title=type("com.xuncorp.voxzen.data.entity.Track").getMethod("getTitle");
                require(title.invoke(tracks.get(0)).equals("First") && title.invoke(tracks.get(1)).equals("Second"),"CUE titles/order lost");
                Method duration=type("com.xuncorp.voxzen.data.entity.Track").getMethod("getDuration");
                require(duration.invoke(tracks.get(0)).equals(10_000L) && duration.invoke(tracks.get(1)).equals(20_000L),"CUE durations regressed");
                Object cueTrack=field(field(((List<?>)field(((List<?>)field(hidden,"entries")).get(0),"rows")).get(0),"song"),"track");
                Object visCompanion=type("spw.cue.SongVisibility").getField("Companion").get(null);
                Method key=Arrays.stream(visCompanion.getClass().getMethods()).filter(m -> m.getName().equals("key")).findFirst().orElseThrow();
                String hiddenKey=(String)key.invoke(visCompanion,cueTrack);
                Object excluded=field(project.invoke(page,originals,true,Set.of(hiddenKey)),"groups");
                require(displayed(song,excluded).size()==2,"Hidden/removed CUE must disappear from Artist Songs");
                require(rows(song,originals).size()==2 && title.invoke(source).equals("Whole album"),"Native artist input was mutated");
                Object input=field(page,"latestInput");
                require(type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue").invoke(input)==null,"Artist projection polluted main Songs state");
                Map<?,?> selectionEntries=(Map<?,?>)field(hidden,"selectionEntries");
                Method id=type("com.xuncorp.voxzen.data.entity.Track").getMethod("getId");
                Set<?> selected=Set.of(id.invoke(tracks.get(0)),id.invoke(tracks.get(1)));
                Class<?> storeType=type("spw.cue.SongVisibility");
                Path visibilityFile=folder.resolve("song-visibility.txt");
                Object store=storeType.getConstructor(Path.class).newInstance(visibilityFile);
                Object recoveryWindow=storeType.getConstructor(Path.class).newInstance(visibilityFile);
                Class<?> removal=type("spw.cue.CueRemoval");
                Method select=removal.getDeclaredMethod("selectedEntries",Map.class,Set.class,storeType);select.setAccessible(true);
                require(((Set<?>)select.invoke(null,selectionEntries,selected,store)).size()==2,"Artist batch selection must include both tracks without main Songs state");
                Object unit=type("kotlin.Unit").getField("INSTANCE").get(null);
                Class<?> f0=type("kotlin.jvm.functions.Function0"), f1=type("kotlin.jvm.functions.Function1");
                Method menu=removal.getDeclaredMethod("menu",f0,f0,f0,f1,f1);menu.setAccessible(true);
                Object items=menu.invoke(null,
                    callback(f0.getName(),a->{storeType.getMethod("hide",String.class).invoke(store,hiddenKey);return unit;}),
                    callback(f0.getName(),a->{storeType.getMethod("remove",String.class).invoke(store,hiddenKey);return unit;}),
                    callback(f0.getName(),a->select.invoke(null,selectionEntries,selected,store)),
                    callback(f1.getName(),a->{storeType.getMethod("remove",Set.class).invoke(store,a[0]);return unit;}),
                    callback(f1.getName(),a->{storeType.getMethod("hide",Set.class).invoke(store,a[0]);return unit;}));
                List<Object> cueTracks=new ArrayList<>();
                List<String> cueKeys=new ArrayList<>();
                List<?> planned=(List<?>)field(((List<?>)field(hidden,"entries")).get(0),"rows");
                for(int i=0;i<2;i++) {
                    Object t=field(field(planned.get(i),"song"),"track");
                    cueTracks.add(t);cueKeys.add((String)key.invoke(visCompanion,t));
                }
                Class<?> filterType=type("spw.cue.CueTrackStatusFilter");
                Method filter=type("spw.cue.CueTrackManagementKt").getMethod("filterManagedCueTracks",List.class,List.class,String.class,filterType,type("spw.cue.SongVisibilityRecords"));
                for(int actionIndex:new int[]{1,0}) {
                    List<?> actions=(List<?>)f0.getMethod("invoke").invoke(items);
                    Object action=actions.get(actionIndex);
                    String label=(String)action.getClass().getMethod("getLabel").invoke(action);
                    require(label.endsWith("（2）"),"Batch menu count must be two: "+label);
                    Object click=action.getClass().getMethod("getOnClick").invoke(action);
                    f0.getMethod("invoke").invoke(click);
                    Object state=Arrays.stream(filterType.getEnumConstants()).filter(v->v.toString().equals(actionIndex==1?"Hidden":"Removed")).findFirst().orElseThrow();
                    List<?> recovered=(List<?>)filter.invoke(null,cueTracks,cueKeys,"",state,records(recoveryWindow));
                    require(recovered.size()==2,"Both hidden/removed tracks must appear in the already-open recovery view");
                    Object reopened=storeType.getConstructor(Path.class).newInstance(visibilityFile);
                    require(((List<?>)filter.invoke(null,cueTracks,cueKeys,"",state,records(reopened))).size()==2,"Both records must survive reopening");
                    storeType.getMethod("restoreAll").invoke(store);
                }
                System.out.println("PASS real menu -> persistence -> recovery: batch hide and remove, two tracks, already-open and reopened views");
                Files.delete(cue);
                require(project.invoke(page,originals,true,Set.of())==null,"Removing CUE must restore original artist rows");
                System.out.println("PASS real host projection: CUE rows, mixed audio, hiding/removal, duration, isolation, missing CUE");
            } finally {
                try(var paths=Files.walk(folder)) {
                    for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
                }
            }
        }
    }
}
