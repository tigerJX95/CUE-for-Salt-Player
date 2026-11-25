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

    static void verifyHookRegistration(Object catalog,Method method) throws Exception {
        Object receiver=hookResolver(catalog);
        Method resolve=Arrays.stream(receiver.getClass().getMethods())
            .filter(m->Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,String.class,List.class}))
            .findFirst().orElseThrow();
        Object site=resolve.invoke(receiver,method.getDeclaringClass().getName(),method.getName(),
            Arrays.stream(method.getParameterTypes()).map(Class::getName).toList());
        Method resolved=(Method)Arrays.stream(site.getClass().getMethods()).filter(m->m.getReturnType()==Method.class && m.getParameterCount()==0)
            .findFirst().orElseThrow().invoke(site);
        require(resolved.equals(method),"Salt Player's actual MethodHook resolver must accept "+method);
    }

    static Object hookResolver(Object catalog) throws Exception {
        Class<?> frame=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Class<?> registrar=frame.getConstructors()[0].getParameterTypes()[0];
        Method registration=registrar.getMethod("hookMethod",String.class,String.class,List.class,
            type("com.xuncorp.spw.workshop.api.hook.MethodHook"));
        List<?> calls=(List<?>)catalog.getClass().getMethod("calls",Method.class).invoke(catalog,registration);
        InvokeInstruction resolution=calls.stream().map(InvokeInstruction.class::cast).filter(call ->
            call.type().stringValue().startsWith("(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;)Lcom/xuncorp/voxzen/workshop/hooks/"))
            .findFirst().orElseThrow();
        Class<?> resolver=type(resolution.owner().asInternalName().replace('/','.'));
        return Arrays.stream(resolver.getFields()).filter(f->Modifier.isStatic(f.getModifiers()) && f.getType()==resolver)
            .findFirst().orElseThrow().get(null);
    }

    static void verifySongsLocate(Object catalog,Method list) throws Exception {
        Object locate=resolve("spw.cue.NativeSongLocate",list);
        Class<?> caller=(Class<?>)field(locate,"caller");
        Method scrollbar=(Method)field(locate,"scrollbar");
        Class<?> composer=type("androidx.compose.runtime.Composer");
        Object receiver=hookResolver(catalog);
        Method resolve=Arrays.stream(receiver.getClass().getMethods())
            .filter(m->Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,String.class,String.class,List.class}))
            .findFirst().orElseThrow();
        Object site=resolve.invoke(receiver,scrollbar.getDeclaringClass().getName(),scrollbar.getName(),caller.getName(),
            Arrays.stream(scrollbar.getParameterTypes()).takeWhile(t->t!=composer).map(Class::getName).toList());
        Method resolved=(Method)Arrays.stream(site.getClass().getMethods()).filter(m->m.getReturnType()==Method.class && m.getParameterCount()==0)
            .findFirst().orElseThrow().invoke(site);
        require(resolved.equals(scrollbar),"Locate must attach to the existing native Songs layout");
        require(field(locate,"icon")!=null,"Host locate glyph missing");
        System.out.println("PASS Songs locate attaches to the native scroll layout and reuses the host glyph");
    }

    static void albumEndThroughHostFrame(Path folder,Object albumEnd,Method finish) throws Exception {
        Class<?> frameType=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Constructor<?> frame=frameType.getConstructors()[0];
        MethodHandle original=MethodHandles.privateLookupIn(finish.getDeclaringClass(),MethodHandles.lookup()).unreflect(finish);
        Object site=frame.getParameterTypes()[1].getConstructor(int.class,Method.class,MethodHandle.class)
            .newInstance(1,finish,original);
        Object hooks=hookRegistrar(folder);
        Object album=frame.newInstance(hooks,site,null,new Object[0]);
        Object ordinary=frame.newInstance(hooks,site,null,new Object[0]);
        Method intercept=albumEnd.getClass().getMethod("interceptNaturalEnd",
            type("com.xuncorp.spw.workshop.api.hook.MethodHookParam"),type("kotlin.jvm.functions.Function0"));
        intercept.invoke(albumEnd,album,function("kotlin.jvm.functions.Function0",true));
        intercept.invoke(albumEnd,ordinary,function("kotlin.jvm.functions.Function0",false));
        require(frameType.getMethod("isOriginalSkipped").invoke(album).equals(true) &&
            frameType.getMethod("getResult").invoke(album)==null,
            "An album continuation must stop the native queue from independently fetching another item");
        require(frameType.getMethod("isOriginalSkipped").invoke(ordinary).equals(false),
            "The native callback must handle the end of an ordinary track or the album's last disc");
        System.out.println("PASS album natural end suppresses the native queue once through the real host hook frame");
    }

    static void wholeSongThroughHostFrame(Path folder,Object song,Object page,Object source,List<?> groups) throws Exception {
        List<?> starts=(List<?>)song.getClass().getMethod("orderedStartCallbacks").invoke(song);
        Method start=starts.stream().map(Method.class::cast).filter(method->method.getParameterCount()==3)
            .findFirst().orElseThrow();
        Class<?> frameType=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Constructor<?> frame=frameType.getConstructors()[0];
        MethodHandle original=MethodHandles.privateLookupIn(start.getDeclaringClass(),MethodHandles.lookup()).unreflect(start);
        Object site=frame.getParameterTypes()[1].getConstructor(int.class,Method.class,MethodHandle.class)
            .newInstance(1,start,original);
        Object hooks=hookRegistrar(folder);
        Object call=frame.newInstance(hooks,site,null,new Object[]{groups,source,false});
        Method before=page.getClass().getDeclaredMethod("beforeOrderedStart",
            type("com.xuncorp.spw.workshop.api.hook.MethodHookParam"));
        before.setAccessible(true);
        Object order=singleton("spw.cue.CueSongOrder");
        Method whole=order.getClass().getMethod("isWholeFile",String.class);
        try {
            before.invoke(page,call);
            require(whole.invoke(order,"source").equals(true) && whole.invoke(order,"normal").equals(false),
                "Clicking the native whole-audio row must select the whole source");
            require(frameType.getMethod("isOriginalSkipped").invoke(call).equals(false),
                "When there is no ordered projection, the native whole-audio play action must still run");
        } finally {
            order.getClass().getMethod("clear").invoke(order);
        }
        System.out.println("PASS whole-song click records the source through the real host hook frame");
    }

    static void nativeRowPalette() throws Exception {
        Class<?> presentation=type("spw.cue.NativeSongPresentationKt");
        require(presentation.getMethod("nativeRowPaletteAvailable").invoke(null).equals(true),
            "Native light and dark row palettes must be available");
        Method bindingGetter=presentation.getDeclaredMethod("getRowPaletteBinding");
        bindingGetter.setAccessible(true);
        Object binding=bindingGetter.invoke(null);
        Constructor<?> pair=(Constructor<?>)field(binding,"pair");
        Constructor<?> palette=(Constructor<?>)field(binding,"palette");
        int accent=(int)field(binding,"accentIndex");
        int text=(int)field(binding,"textIndex");
        require(accent!=text,"Accent and title must be separate palette entries");
        List<List<Long>> originals=new ArrayList<>();
        for (int variant=0; variant<2; variant++) {
            List<Long> colors=new ArrayList<>();
            for (int index=0; index<palette.getParameterCount(); index++) {
                colors.add(0xff10203000000000L+(variant*40L+index)*0x100000000L);
            }
            originals.add(colors);
        }
        Object source=pair.newInstance(palette.newInstance(originals.get(0).toArray()),
            palette.newInstance(originals.get(1).toArray()));
        Method read=binding.getClass().getMethod("shadesOf",Object.class);
        Method neutral=binding.getClass().getMethod("withNeutralAccent",List.class);
        read.setAccessible(true);
        neutral.setAccessible(true);
        Object originalColors=read.invoke(binding,source);
        require(originalColors.equals(originals),"Palette binding must preserve the packed colors");
        Object neutralPalette=neutral.invoke(binding,originalColors);
        List<List<Long>> expected=originals.stream().map(colors->{
            List<Long> copy=new ArrayList<>(colors);
            copy.set(accent,colors.get(text));
            return copy;
        }).toList();
        require(read.invoke(binding,neutralPalette).equals(expected),"Only the accent should use the title color");
        require(read.invoke(binding,source).equals(originals),"Rows outside the override must keep the original palette");
        System.out.println("PASS native row color override keeps text visible in both palettes and preserves the shared theme");
    }

    static void retainedSongsOrder(Object hooks,Object page,Constructor<?> group,Object artistProjection,List<?> tracks,Object ordinary)
        throws Exception {
        Object plan=((List<?>)field(artistProjection,"entries")).getFirst();
        Map<String,Object> byKey=new HashMap<>();
        for (Object row:(List<?>)field(plan,"rows")) {
            if (!row.getClass().getSimpleName().equals("Cue")) continue;
            Object song=field(row,"song");
            byKey.put((String)field(song,"key"),song);
        }
        Object target=field(page,"target");
        Object nativeState=target.getClass().getMethod("filterTracks",List.class,Object.class,boolean.class,String.class)
            .invoke(target,List.of(ordinary),((Constructor<?>)field(target,"songState"))
                .getParameterTypes()[3].getEnumConstants()[0],false,"");
        List<?> nativeGroups=(List<?>)target.getClass().getMethod("groupEntities",Object.class).invoke(target,nativeState);
        Object groupKey=((Field)field(target,"groupKey")).get(nativeGroups.getFirst());
        List<?> sorted=List.of(group.newInstance(groupKey,List.of(tracks.get(0),ordinary,tracks.get(1))));
        Method remap=page.getClass().getDeclaredMethod("remapGroups",List.class,Map.class,boolean.class);
        remap.setAccessible(true);
        Object songs=remap.invoke(page,sorted,byKey,false);
        List<?> playbackRows=(List<?>)field(songs,"playbackRows");
        Object firstCue=field(playbackRows.get(0),"cue"), secondCue=field(playbackRows.get(2),"cue");
        Method locate=songs.getClass().getMethod("locateKey",String.class,type("spw.cue.CueTrack"));
        locate.setAccessible(true);
        String firstKey=(String)locate.invoke(songs,"source",firstCue), secondKey=(String)locate.invoke(songs,"source",secondCue);
        require(firstKey!=null && secondKey!=null && !firstKey.equals(secondKey),
            "Each CUE segment must have its own locate key");
        require(locate.invoke(songs,"normal",null)==null && locate.invoke(songs,"source",null)==null,
            "Ordinary tracks use the native path and a hidden whole source has no locate target");
        List<?> withWhole=List.of(group.newInstance(groupKey,List.of(ordinary,tracks.get(1),
            sourceOf(playbackRows.get(0)),tracks.get(0))));
        Object visibleWhole=remap.invoke(page,withWhole,byKey,false);
        require(locate.invoke(visibleWhole,"source",null).equals("source") &&
            locate.invoke(visibleWhole,"source",secondCue).equals(secondKey),
            "Reordering must preserve the distinct whole-audio and segment keys");
        Object reordered=remap.invoke(page,List.of(group.newInstance(groupKey,List.of(tracks.get(1),ordinary,tracks.get(0)))),
            byKey,false);
        verifyLocateLayouts(hooks,target,page,songs,reordered,visibleWhole,firstKey,secondKey);
        retainProjectionAcrossRefresh(hooks,page,group,songs,sourceOf(playbackRows.get(0)),ordinary);
        Object rendered=field(songs,"renderingGroups");
        // Emulate the host retaining an earlier row callback after the list has been rebuilt.
        remap.invoke(page,sorted,byKey,false);
        Method resolve=page.getClass().getDeclaredMethod("orderedStartGroups",Object.class);
        resolve.setAccessible(true);
        require(resolve.invoke(page,rendered)==songs && resolve.invoke(page,songs)==songs,
            "Row and toolbar callbacks must retain the displayed Songs projection");
        require(resolve.invoke(page,field(artistProjection,"renderingGroups"))==null,
            "Artist callbacks must not activate the main Songs order");

        Object order=singleton("spw.cue.CueSongOrder");
        Class<?> f0=type("kotlin.jvm.functions.Function0"), f3=type("kotlin.jvm.functions.Function3");
        Method step=order.getClass().getMethod("step",String.class,String.class,Path.class,int.class,Set.class,f0);
        Object stop=callback(f0.getName(),a->{throw new AssertionError("The list has a next row");});
        List<String> played=new ArrayList<>();
        try {
            order.getClass().getMethod("start",List.class,int.class,f0,boolean.class,boolean.class,f3)
                .invoke(order,field(songs,"playbackRows"),0,function(f0.getName(),true),true,true,
                    callback(f3.getName(),a->{
                        Object cue=field(a[0],"cue");
                        played.add(cue==null?(String)field(field(a[0],"source"),"id"):(String)field(cue,"title"));
                        return type("kotlin.Unit").getField("INSTANCE").get(null);
                    }));
            require(Boolean.TRUE.equals(step.invoke(order,"CircleEnd","source",null,1,Set.of(),stop)) &&
                Boolean.TRUE.equals(step.invoke(order,"CircleEnd","normal",null,1,Set.of(),stop)),
                "Songs playback must consume both native Next requests");
            require(played.equals(List.of("First","normal","Second")),
                "Projected Songs order must play the ordinary song between two CUE rows from the same source");
        } finally {
            order.getClass().getMethod("clear").invoke(order);
        }
        System.out.println("PASS retained Songs callback advances through the displayed mixed order");
    }

    static Object sourceOf(Object row) throws Exception { return field(field(row,"source"),"entity"); }

    static void retainProjectionAcrossRefresh(Object hooks,Object page,Constructor<?> group,Object projected,Object source,Object ordinary)
        throws Exception {
        Object target=field(page,"target");
        List<Object> entities=List.of(source,ordinary);
        Constructor<?> nativeState=(Constructor<?>)field(target,"songState");
        Object sort=nativeState.getParameterTypes()[3].getEnumConstants()[0];
        Method projection=target.getClass().getMethod("projection",Object.class,List.class,int.class);
        Object original=target.getClass().getMethod("filterTracks",List.class,Object.class,boolean.class,String.class)
            .invoke(target,entities,sort,false,"");
        List<?> rawGroups=List.of(group.newInstance("A",entities));
        Object refreshed=projection.invoke(target,original,rawGroups,2);
        Object published=projection.invoke(target,original,projected,3);
        Class<?> pageType=page.getClass();
        Constructor<?> inputType=type(pageType.getName()+"$FilterInput").getDeclaredConstructors()[0];
        inputType.setAccessible(true);
        Object oldInput=inputType.newInstance(entities,original,sort,false,"");
        Object newInput=inputType.newInstance(entities,refreshed,sort,false,"");
        Class<?> mutableFlow=type("kotlinx.coroutines.flow.MutableStateFlow");
        Method set=mutableFlow.getMethod("setValue",Object.class), get=mutableFlow.getMethod("getValue");
        Object pageState=field(page,"pageState"), latest=field(page,"latestInput"), mount=field(page,"mount");
        Object priorMount=get.invoke(mount);
        Object flow=type("kotlinx.coroutines.flow.StateFlowKt").getMethod("MutableStateFlow",Object.class).invoke(null,refreshed);
        Constructor<?> activation=priorMount.getClass().getDeclaredConstructor(boolean.class,long.class);
        activation.setAccessible(true);
        Constructor<?> publication=type(pageType.getName()+"$Publication").getDeclaredConstructors()[0];
        publication.setAccessible(true);
        Field publishField=pageType.getDeclaredField("publication");
        publishField.setAccessible(true);
        Field diagnosing=pageType.getDeclaredField("diagnosing");
        diagnosing.setAccessible(true);
        boolean priorDiagnosing=diagnosing.getBoolean(page);
        Method draw=pageType.getDeclaredMethod("groupsForDrawing",Object.class);
        draw.setAccessible(true);
        try {
            set.invoke(pageState,flow);
            set.invoke(latest,newInput);
            set.invoke(mount,activation.newInstance(true,1L));
            publishField.set(page,publication.newInstance(flow,oldInput,false,Set.of(),0L,published));
            require(draw.invoke(page,rawGroups)==projected,"A host refresh must keep drawing the existing CUE projection");
            diagnosing.setBoolean(page,true);
            verifyListRows(hooks,target,page,rawGroups,3);
            set.invoke(latest,inputType.newInstance(entities,refreshed,sort,false,"new search"));
            require(draw.invoke(page,rawGroups)==rawGroups,"New searches must render their own results");
            set.invoke(flow,published);
            Object equivalent=projection.invoke(target,original,new ArrayList<>((List<?>)target.getClass()
                .getMethod("groupEntities",Object.class).invoke(target,original)),2);
            require(equivalent!=original && equivalent.equals(original),"The host must produce an equivalent native value");
            require(afterNativeSongsFilter(hooks,target,page,entities,sort,"",equivalent)==published,
                "Equivalent results must reuse the projected state before the host can publish raw rows");
            require(afterNativeSongsFilter(hooks,target,page,entities,sort,"new search",equivalent)==equivalent,
                "A new query must be allowed to publish its own results");
            require(afterNativeSongsFilter(hooks,target,page,List.of(source),sort,"",equivalent)==equivalent,
                "A changed library must be allowed to publish its own results");
        } finally {
            diagnosing.setBoolean(page,priorDiagnosing);
            publishField.set(page,null);
            set.invoke(mount,priorMount);
            set.invoke(latest,(Object)null);
            set.invoke(pageState,(Object)null);
        }
        System.out.println("PASS Songs retains its projection during an equivalent host refresh");
    }

    static Object afterNativeSongsFilter(Object hooks,Object target,Object page,List<?> entities,Object sort,String query,
                                         Object nativeResult) throws Exception {
        Class<?> frameType=type("com.xuncorp.voxzen.workshop.hooks.WorkshopMethodFrame");
        Constructor<?> frame=frameType.getConstructors()[0];
        Method filter=(Method)target.getClass().getMethod("getFilter").invoke(target);
        MethodHandle filterHandle=MethodHandles.privateLookupIn(filter.getDeclaringClass(),MethodHandles.lookup()).unreflect(filter);
        Object site=frame.getParameterTypes()[1].getConstructor(int.class,Method.class,MethodHandle.class)
            .newInstance(3,filter,filterHandle);
        Object call=frame.newInstance(hooks,site,null,new Object[]{new ArrayList<>(entities),sort,false,query,null});
        Method complete=Arrays.stream(frameType.getMethods()).filter(m->m.getName().startsWith("complete$")).findFirst().orElseThrow();
        complete.invoke(call,nativeResult,null);
        page.getClass().getMethod("afterFilter",type("com.xuncorp.spw.workshop.api.hook.MethodHookParam")).invoke(page,call);
        return frameType.getMethod("getResult").invoke(call);
    }

    static Object measuredSongRow(int count,int index,String key,int offset,int size) throws Exception {
        Class<?> item=type("androidx.compose.foundation.lazy.LazyListItemInfo");
        Object visible=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{item},(p,m,a)->switch(m.getName()){
            case "getIndex" -> index;
            case "getKey" -> key;
            case "getOffset" -> offset;
            case "getSize" -> size;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        Class<?> info=type("androidx.compose.foundation.lazy.LazyListLayoutInfo");
        return java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{info},(p,m,a)->switch(m.getName()){
            case "getTotalItemsCount" -> count;
            case "getVisibleItemsInfo" -> List.of(visible);
            case "getViewportStartOffset" -> -80;
            case "getBeforeContentPadding" -> 80;
            case "getViewportEndOffset" -> 620;
            case "getAfterContentPadding" -> 20;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    static void verifyLocateLayouts(Object hooks,Object target,Object page,Object songs,Object reordered,Object withWhole,
                                    String firstKey,String secondKey) throws Exception {
        Class<?> infoType=type("androidx.compose.foundation.lazy.LazyListLayoutInfo");
        Object old=measuredSongRow(6,2,secondKey,90,48);
        Object shortLayout=measuredSongRow(5,2,secondKey,90,48);
        Object remeasured=measuredSongRow(6,2,secondKey,90,48);
        Method make=page.getClass().getDeclaredMethod("locateLayout",List.class,infoType);
        make.setAccessible(true);
        Field diagnosing=page.getClass().getDeclaredField("diagnosing");
        diagnosing.setAccessible(true);
        boolean previous=diagnosing.getBoolean(page);
        try {
            diagnosing.setBoolean(page,true);
            Object first=make.invoke(page,verifyListRows(hooks,target,page,songs,3),old);
            Object next=make.invoke(page,verifyListRows(hooks,target,page,reordered,3),old);
            Object whole=make.invoke(page,verifyListRows(hooks,target,page,withWhole,4),old);
            Method index=next.getClass().getMethod("indexOf",String.class);
            Method matches=next.getClass().getMethod("matches",infoType);
            require(index.invoke(first,secondKey).equals(4) && index.invoke(next,secondKey).equals(2) &&
                index.invoke(next,firstKey).equals(4) && index.invoke(whole,"source").equals(4),
                "Locate indices must follow the submitted lazy list and use the actual song key");
            require(matches.invoke(first,remeasured).equals(false) && matches.invoke(next,old).equals(false) &&
                matches.invoke(next,shortLayout).equals(false) && matches.invoke(next,remeasured).equals(true),
                "Locate must reject unmeasured, shorter and reordered layouts until their visible keys match");
            Method reveal=next.getClass().getMethod("visibleRevealDelta",infoType,String.class,int.class);
            require(reveal.invoke(next,remeasured,secondKey,36).equals(0) &&
                reveal.invoke(next,measuredSongRow(6,2,secondKey,12,48),secondKey,36).equals(-24) &&
                reveal.invoke(next,measuredSongRow(6,2,secondKey,590,48),secondKey,36).equals(38) &&
                reveal.invoke(next,remeasured,firstKey,36)==null,
                "Visible songs must stay put or scroll only enough to reveal the row");
        } finally {
            diagnosing.setBoolean(page,previous);
        }
        System.out.println("PASS Songs locate follows remeasured rows by key after list changes");
    }

    static List<?> verifyListRows(Object hooks,Object target,Object page,Object groups,int expected) throws Exception {
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
        List<Object> rows=new ArrayList<>();
        Constructor<?> record=type(page.getClass().getName()+"$ScopeCall").getDeclaredConstructor(Method.class,Object[].class);
        record.setAccessible(true);
        Object[] args=new Object[types.length];
        for(int i=0;i<args.length;i++){
            if(types[i]==long.class)args[i]=0L;
            if(types[i]==int.class)args[i]=0;
            if(types[i]==boolean.class)args[i]=false;
        }
        args[0]=groups;
        args[args.length-1]=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{scope},(proxy,method,values)->{
            if(method.equals(batch))sizes.add((Integer)values[0]);
            if(method.getReturnType()==void.class)rows.add(record.newInstance(method,values.clone()));
            return null;
        });
        Object call=frame.newInstance(hooks,site,null,args);
        page.getClass().getMethod("beforeList",type("com.xuncorp.spw.workshop.api.hook.MethodHookParam")).invoke(page,call);
        Object diagnostics=field(page,"diagnostics");
        require(frameType.getMethod("isOriginalSkipped").invoke(call).equals(true) &&
            sizes.stream().mapToInt(Integer::intValue).sum()==expected,
            "The real native list builder must submit " + expected + " track rows. Batches: " + sizes +
                "\n" + diagnostics.getClass().getMethod("report").invoke(diagnostics));
        return rows;
    }

    static void verifyAlreadyLoadedSongs(Path folder,Object target,Object page,Object sort,Object firstDisc,Object store) throws Exception {
        Path secondFolder=Files.createDirectory(folder.resolve("CD2"));
        Path secondAudio=Files.write(secondFolder.resolve("album.wav"),new byte[]{0});
        Files.copy(folder.resolve("album.cue"),secondFolder.resolve("album.cue"));
        Object secondDisc=track(secondAudio,"second-disc");
        Class<?> targetType=target.getClass(), pageType=page.getClass();
        Method filter=targetType.getMethod("filterTracks",List.class,Object.class,boolean.class,String.class);
        Object loaded=filter.invoke(target,List.of(firstDisc,secondDisc),sort,true,"");
        Object searched=filter.invoke(target,List.of(firstDisc,secondDisc),sort,true,"missing");
        Class<?> mutableFlow=type("kotlinx.coroutines.flow.MutableStateFlow");
        Method set=mutableFlow.getMethod("setValue",Object.class),get=mutableFlow.getMethod("getValue");
        Object flow=type("kotlinx.coroutines.flow.StateFlowKt").getMethod("MutableStateFlow",Object.class).invoke(null,searched);
        set.invoke(field(page,"pageState"),flow);
        Method adopt=pageType.getDeclaredMethod("adoptExistingSongState",mutableFlow,Object.class);
        adopt.setAccessible(true);
        Object latest=field(page,"latestInput");
        adopt.invoke(page,flow,searched);
        adopt.invoke(page,flow,loaded);
        require(get.invoke(latest)==null,"An empty search or stale state must not seed Songs");
        set.invoke(flow,loaded);
        adopt.invoke(page,flow,loaded);
        Object input=get.invoke(latest);
        require(input!=null && field(input,"original")==loaded && field(input,"sort")==sort &&
            Boolean.TRUE.equals(field(input,"grouped")) &&
            new HashSet<>((List<?>)field(input,"entities")).equals(Set.of(firstDisc,secondDisc)),
            "The already-loaded Songs list must retain its native sorting and original tracks");
        for(String name:List.of("diagnosing","callbacksVerified")) {
            Field flag=pageType.getDeclaredField(name);
            flag.setAccessible(true);
            flag.setBoolean(page,true);
        }
        Object mount=field(page,"mount");
        Constructor<?> activation=get.invoke(mount).getClass().getDeclaredConstructor(boolean.class,long.class);
        activation.setAccessible(true);
        set.invoke(mount,activation.newInstance(true,1L));
        Method mountCurrent=pageType.getDeclaredMethod("mountCurrent",mutableFlow,Object.class,input.getClass(),
            boolean.class,Set.class,long.class);
        mountCurrent.setAccessible(true);
        mountCurrent.invoke(page,flow,loaded,input,false,Set.of(),0L);
        Object projected=get.invoke(flow);
        require(targetType.getMethod("count",Object.class).invoke(target,projected).equals(6),
            "Both whole-disc files and four CUE tracks must be listed without another host filter callback");
        Object groups=targetType.getMethod("groupEntities",Object.class).invoke(target,projected);
        verifyListRows(hookRegistrar(Files.createDirectory(folder.resolve("already-loaded-song-plugins"))),
            target,page,groups,6);
        System.out.println("PASS already-loaded Songs expands two CUE discs without waiting for a filter callback");

        List<?> keys=new ArrayList<>(((Map<?,?>)field(groups,"selectionEntries")).values());
        Set<?> removed=Set.copyOf(keys.subList(0,2)), hidden=Set.copyOf(keys.subList(2,4));
        store.getClass().getMethod("remove",Set.class).invoke(store,removed);
        store.getClass().getMethod("hide",Set.class).invoke(store,hidden);
        mountCurrent.invoke(page,flow,projected,input,false,new HashSet<>(keys),0L);
        Object filtered=get.invoke(flow);
        require(targetType.getMethod("count",Object.class).invoke(target,filtered).equals(2),
            "Hidden and removed tracks must leave both WAV rows visible");
        Object diagnostics=field(page,"diagnostics");
        String report=(String)diagnostics.getClass().getMethod("report").invoke(diagnostics);
        require(report.contains("已隐藏 2 条、已从曲库移除 2 条") && report.contains("可在「管理分轨」恢复"),
            "Songs diagnostics must distinguish recognized but excluded tracks: "+report);
        System.out.println("PASS Songs reports separate hide and removal counts with recovery guidance");
    }

    static void verifySongRowInput(Object song) throws Exception {
        Object selection=song.getClass().getMethod("getSelection").invoke(song);
        require(selection!=null,"Songs must keep native checkbox selection");
        Class<?> stateType=((Method)field(selection,"scrollState")).getReturnType();
        Class<?> lazy=type("androidx.compose.foundation.lazy.LazyListState");
        Object state=stateType.getConstructor(lazy).newInstance(lazy.getConstructor(int.class,int.class).newInstance(0,0));
        Method keyboard=Arrays.stream(stateType.getMethods())
            .filter(method->method.getReturnType()==void.class && Arrays.equals(method.getParameterTypes(),new Class<?>[]{boolean.class}))
            .findFirst().orElseThrow();
        Method keys=selection.getClass().getMethod("keys",Object.class);
        Method write=(Method)field(selection,"setSelectedKeys");
        String cue="spw.cue.support:songs:3:wav:1", normal="ordinary";
        Method rowInput=Arrays.stream(type("spw.cue.NativeSongCueKt").getDeclaredMethods())
            .filter(method->method.getName().startsWith("cueSongRowInput")).findFirst().orElseThrow();
        Class<?> modifier=type("androidx.compose.ui.Modifier"), f0=type("kotlin.jvm.functions.Function0");
        Object base=modifier.getField("Companion").get(null);
        Object interaction=type("androidx.compose.foundation.interaction.InteractionSourceKt")
            .getMethod("MutableInteractionSource").invoke(null);
        int[] plays={0};
        Object play=callback(f0.getName(),unused->{ plays[0]++; return singleton("kotlin.Unit"); });
        for (boolean keyboardUsed:List.of(false,true)) {
            keyboard.invoke(state,keyboardUsed);
            for (Set<String> selected:List.of(Set.of(normal),Set.of(normal,cue))) {
                write.invoke(state,selected);
                Object input=rowInput.invoke(null,base,selected.contains(cue),0L,interaction,true,play);
                Object gesture=modifier.getMethod("foldIn",Object.class,type("kotlin.jvm.functions.Function2"))
                    .invoke(input,null,callback("kotlin.jvm.functions.Function2",args->
                        args[1].getClass().getSimpleName().equals("CombinedClickableElement") ? args[1] : args[0]));
                require(gesture!=null,"CUE song body must distinguish single and double clicks");
                int before=plays[0];
                for(String action:List.of("onClick","onDoubleClick")) {
                    f0.getMethod("invoke").invoke(field(gesture,action));
                }
                require(plays[0]==before+2 && keys.invoke(selection,state).equals(selected),
                    "Both body gestures must play even with native keyboard navigation or existing checkmarks");
            }
        }
        Method toggle=selection.getClass().getMethod("toggle",Object.class,String.class);
        toggle.invoke(selection,state,cue);
        require(keys.invoke(selection,state).equals(Set.of(normal)),"Checkbox must preserve the normal song selection");
        toggle.invoke(selection,state,cue);
        require(keys.invoke(selection,state).equals(Set.of(normal,cue)),"Checkbox must still add the CUE track");
        require(plays[0]==8,"Checkbox selection must never request playback");
        System.out.println("PASS CUE single and double clicks play independently of native keyboard and checkbox selection");
    }

    static void verifyWholeWaveCoverRows(Path folder,Object target,Object page,List<?> originals,Object source)
        throws Exception {
        Object hooks=hookRegistrar(folder);
        Method batch=(Method)target.getClass().getMethod("getItemBatch").invoke(target);
        Method rawPlans=page.getClass().getDeclaredMethod("unprojectedCoverRows",List.class);
        rawPlans.setAccessible(true);
        List<?> plans=(List<?>)rawPlans.invoke(page,originals);
        boolean rawSource=false;
        for (Object plan:plans) {
            for (Object row:(List<?>)field(plan,"rows")) rawSource|=field(row,"unprojectedWave")==source;
        }
        require(rawSource,"The original WAV entity must drive cover lookup");
        List<?> raw=verifyListRows(hooks,target,page,originals,2);
        Object projected=field(page.getClass().getMethod("projectArtistGroups",List.class,boolean.class,Set.class)
            .invoke(page,originals,false,Set.of()),"groups");
        List<?> projectionPlans=(List<?>)field(projected,"entries");
        boolean projectedSource=false;
        for (Object plan:projectionPlans) {
            for (Object row:(List<?>)field(plan,"rows")) {
                if (!row.getClass().getSimpleName().equals("Original") || !field(row,"sourceId").equals("source")) continue;
                Object artwork=field(row,"artwork");
                projectedSource=artwork!=null && artwork.getClass().getMethod("isWave").invoke(artwork).equals(true);
            }
        }
        require(projectedSource,"The expanded view must share the WAV source artwork with the original row");
        List<?> expanded=verifyListRows(hooks,target,page,projected,4);
        for (List<?> emitted:List.of(raw,expanded)) {
            boolean installed=false;
            for (Object entry:emitted) {
                if (!field(entry,"method").equals(batch)) continue;
                Object content=((Object[])field(entry,"args"))[5];
                Object body=field(content,"_block");
                installed|=body.getClass().getName().startsWith(page.getClass().getName()+"$");
            }
            require(installed,"The real native lazy list must receive the plugin's row content callback");
        }
        System.out.println("PASS whole WAV cover content is mounted on the actual native and expanded song lists");
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
            callback("kotlin.jvm.functions.Function4",a->project.invoke(page,a[0],a[1],a[2])),function("kotlin.jvm.functions.Function1",null));

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
        verifyListRows(hooks,target,page,projected,3);
        Object cueOnly=target.getClass().getMethod("replaceTracks",Object.class,List.class)
            .invoke(target,originals.getFirst(),List.of(cueSource));
        verifyListRows(hooks,target,page,field(project.invoke(page,List.of(cueOnly),true,Set.of()),"groups"),2);
        System.out.println("PASS Original Artist StateFlow and native list builder submit CUE-only and mixed artist rows");
    }

    static Class<?> type(String name) throws Exception { return Class.forName(name, false, loader); }
    static Object singleton(String name) throws Exception { return type(name).getField("INSTANCE").get(null); }
    static Object resolve(String name) throws Exception {
        Object c = type(name).getField("Companion").get(null);
        return c.getClass().getMethod("resolve").invoke(c);
    }
    static Object resolve(String name,Method method) throws Exception {
        Object c=type(name).getField("Companion").get(null);
        return c.getClass().getMethod("resolve",Method.class).invoke(c,method);
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
        values[0]=id;values[1]=id.equals("normal")?"Ordinary song":"Whole album";values[2]="Artist";values[3]="Album";values[4]="Artist";
        values[11]=path.toString();values[12]=true;values[17]=30_000L;
        values[21]=type("com.xuncorp.voxzen.data.entity.ReplayGain").getConstructor().newInstance();
        return ctor.newInstance(values);
    }

    static void verifyAlbumActionPlacement(Object album,List<?> tracks) throws Exception {
        Method builder=(Method)field(album,"listBuilder");
        builder.setAccessible(true);
        Class<?> scopeType=builder.getParameterTypes()[builder.getParameterCount()-1];
        Method discNumber=(Method)field(album,"discNumber");
        require(discNumber!=null,"Native disc heading binding missing");
        Constructor<?> disc=discNumber.getDeclaringClass().getConstructor(int.class,int.class,long.class);
        Class<?> groupType=((Field)field(album,"entities")).getDeclaringClass();
        Constructor<?> group=groupType.getConstructor(Object.class,List.class);
        Class<?> f0=type("kotlin.jvm.functions.Function0"), f1=type("kotlin.jvm.functions.Function1");
        Class<?> f2=type("kotlin.jvm.functions.Function2"), f3=type("kotlin.jvm.functions.Function3");
        Object unit=type("kotlin.Unit").getField("INSTANCE").get(null);
        Method isDisc=album.getClass().getMethod("isVisibleDiscHeading",Object.class);
        for (List<Integer> numbers:List.of(List.of(1),List.of(0,1,2),List.of(0))) {
            List<Object> groups=new ArrayList<>();
            List<String> ids=new ArrayList<>();
            for (int i=0;i<numbers.size();i++) {
                Object track=tracks.get(i);
                ids.add((String)track.getClass().getMethod("getId").invoke(track));
                groups.add(group.newInstance(disc.newInstance(numbers.get(i),1,30_000L),List.of(track)));
            }
            List<Object[]> replay=new ArrayList<>();
            Object scope=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{scopeType},(p,m,a)->{
                replay.add(a.clone());
                return null;
            });
            Object pair=album.getClass().getMethod("recordList",Object.class).invoke(album,scope);
            Object proxy=pair.getClass().getMethod("getFirst").invoke(pair);
            Object recorder=pair.getClass().getMethod("getSecond").invoke(pair);
            Object[] args=new Object[builder.getParameterCount()];
            for (int i=0;i<args.length;i++) {
                if (builder.getParameterTypes()[i]==boolean.class) args[i]=false;
                if (builder.getParameterTypes()[i]==long.class) args[i]=0L;
            }
            args[0]=groups;
            args[args.length-1]=proxy;
            builder.invoke(null,args);
            List<Object[]> original=new ArrayList<>();
            for (Object entry:(List<?>)field(recorder,"entries")) original.add((Object[])field(entry,"args"));
            Method place=recorder.getClass().getMethod("placeAlbumActions",f1,f3,f0);
            place.invoke(recorder,callback(f1.getName(),a->isDisc.invoke(album,a[0])),
                function(f3.getName(),unit),callback(f0.getName(),a->{replay.add(new Object[]{"controls"});return unit;}));
            if (ids.size()==1) {
                recorder.getClass().getMethod("apply",boolean.class,Map.class,f1)
                    .invoke(recorder,false,Map.of(),function(f1.getName(),true));
            } else {
                recorder.getClass().getMethod("applyByTrack",List.class,Set.class,f2,f0,Map.class)
                    .invoke(recorder,ids,Set.of(),function(f2.getName(),unit),function(f0.getName(),unit),Map.of());
            }
            if (numbers.stream().allMatch(n->n==0)) {
                require(replay.size()==original.size()+1 && replay.get(1)[0].equals("controls"),
                    "An unnumbered disc must put the actions above its tracks");
                replay.remove(1);
            }
            require(replay.size()==original.size(),"The native album list must retain its item count");
            boolean wrapped=false;
            for (int i=0;i<original.size();i++) {
                Object[] before=original.get(i), after=replay.get(i);
                if (!wrapped && Boolean.TRUE.equals(isDisc.invoke(album,before[0]))) {
                    require(before.length==3 && after[0]==before[0] && after[1]==before[1] &&
                        after[2]!=before[2] && f3.isInstance(after[2]),"The first visible native disc must gain the actions");
                    wrapped=true;
                } else if (ids.size()==1 && before.length==6) {
                    require(Arrays.equals(Arrays.copyOf(before,5),Arrays.copyOf(after,5)),
                        "Single-disc row metadata must be preserved");
                } else {
                    require(Arrays.equals(before,after),"The other native headers and rows must be replayed unchanged");
                }
            }
            require(wrapped==numbers.stream().anyMatch(n->n!=0),"Disc heading decoration count");
        }
        System.out.println("PASS album actions wrap only the first visible native disc heading, with an unnumbered fallback");
    }

    public static void main(String[] args) throws Exception {
        require(args.length==2,"Usage: HostRegressionGate.java PLUGIN_JAR HOST_ARCHIVE");
        Path plugin=Path.of(args[0]).toAbsolutePath(), host=Path.of(args[1]).toAbsolutePath();
        require(Files.isRegularFile(plugin) && Files.isRegularFile(host),"Plugin and host archive must exist");
        try(URLClassLoader isolated=new URLClassLoader(new URL[]{plugin.toUri().toURL(),host.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            loader=isolated;
            Object catalog=singleton("spw.cue.HostCatalog");
            catalog.getClass().getMethod("prepare").invoke(catalog);
            nativeRowPalette();
            Object album=resolve("spw.cue.AlbumHookTarget");
            require(album.getClass().getMethod("getCover").invoke(album)!=null,"Album cover binding missing");
            require(album.getClass().getMethod("getPlayback").invoke(album)!=null,"Album playback binding missing");
            Object cover=album.getClass().getMethod("getCover").invoke(album);
            Method image=(Method)cover.getClass().getMethod("getRender").invoke(cover);
            Class<?> coverOwner=type("com.xuncorp.voxzen.service.PlaybackService").getDeclaredField("imageBitmapOwner").getType();
            Class<?> playbackMonitor=type("com.xuncorp.voxzen.service.PlaybackMonitor");
            Method publishCover=type(playbackMonitor.getName()+"Kt").getMethod("updateImageBitmap",playbackMonitor,coverOwner);
            require(publishCover.getReturnType()==void.class,"Cover transition needs the native void publication point");
            verifyHookRegistration(catalog,publishCover);
            System.out.println("PASS playback cover notification accepts the Workshop hook");
            for(String name:List.of("gridCaller","artistCaller","songCaller")) {
                String caller=(String)field(cover,name);
                require(caller!=null,"Missing cover caller: "+name);
                catalog.getClass().getMethod("requireSingleCall",Class.class,Method.class).invoke(catalog,type(caller),image);
            }
            require(field(cover,"platformPath")!=null,"Cover audio-path conversion missing");
            Object song=resolve("spw.cue.SongHookTarget");
            Method nativeList=(Method)song.getClass().getMethod("getNativeList").invoke(song);
            verifySongsLocate(catalog,nativeList);
            verifySongRowInput(song);
            Object companion=type("spw.cue.ArtistSongTarget").getField("Companion").get(null);
            Object artist=companion.getClass().getMethod("resolve",Method.class).invoke(companion,nativeList);
            require(artist!=null,"Artist Songs binding missing");
            verifyHookRegistration(catalog,(Method)artist.getClass().getMethod("getStateGetter").invoke(artist));
            Class<?> artistSearchType=type("spw.cue.ArtistSongSearchBinding");
            Object search=artist.getClass().getMethod("getSearch").invoke(artist);
            require(search!=null,"Artist search producer binding missing");
            verifyHookRegistration(catalog,(Method)artistSearchType.getMethod("getProducer").invoke(search));
            for (Object start : (List<?>)song.getClass().getMethod("orderedStartCallbacks").invoke(song)) {
                verifyHookRegistration(catalog,(Method)start);
            }
            Object albumEnd=singleton("spw.cue.HostPlaybackEnd");
            Object albumEndBinding=albumEnd.getClass().getMethod("resolve").invoke(albumEnd);
            Method finish=(Method)albumEndBinding.getClass().getMethod("getFinish").invoke(albumEndBinding);
            verifyHookRegistration(catalog,finish);
            verifyHookRegistration(catalog,(Method)albumEndBinding.getClass().getMethod("getPeek").invoke(albumEndBinding));
            System.out.println("PASS Artist Songs search and album end hooks accepted by Salt Player's actual Hook resolver");
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
                Object parser=singleton("spw.cue.CueParser");
                Object sheet=parser.getClass().getMethod("read",Path.class).invoke(parser,cue);
                List<?> endingCueTracks=(List<?>)sheet.getClass().getMethod("getTracks").invoke(sheet);
                albumEndThroughHostFrame(Files.createDirectory(folder.resolve("album-end-plugins")),albumEnd,finish);
                if (!taskbarMarkers.isEmpty()) {
                    taskbarProgressThroughHostFrame(Files.createDirectory(folder.resolve("host-plugins")),endingCueTracks);
                }
                Object source=track(audio,"source"), normal=track(ordinary,"normal");
                verifyAlbumActionPlacement(album,List.of(source,normal,track(ordinary,"third")));
                Object playback=album.getClass().getMethod("getPlayback").invoke(album);
                Class<?> mediaItem=type("com.xuncorp.spw.workshop.api.PlaybackExtensionPoint$MediaItem");
                Object libraryItem=mediaItem.getConstructor(String.class,String.class,String.class,String.class,String.class,
                    String.class,int.class,String.class,int.class,long.class,boolean.class,boolean.class)
                    .newInstance("Whole album","Artist","Album","Artist",audio.toString(),"source",0,"",0,30_000L,false,true);
                Method librarySource=playback.getClass().getDeclaredMethod("librarySource",mediaItem);
                librarySource.setAccessible(true);
                Object windowMedia=field(librarySource.invoke(playback,libraryItem),"media");
                Method converter=(Method)catalog.getClass().getMethod("getMediaConverter").invoke(catalog);
                Object convertedMedia=converter.invoke(null,source);
                for (Method property:windowMedia.getClass().getDeclaredMethods()) {
                    if (property.getParameterCount()==0 && property.getReturnType()==String.class &&
                        !Modifier.isStatic(property.getModifiers()) && !property.getName().equals("toString")) {
                        require(Objects.equals(property.invoke(windowMedia),property.invoke(convertedMedia)),
                            "The window must hand the host the same native media fields as album playback");
                    }
                }
                System.out.println("PASS window playback builds the same native media as album playback");
                Constructor<?> group=(Constructor<?>)field(song,"group");
                List<?> originals=List.of(group.newInstance("A",List.of(source,normal)));
                Class<?> pageType=type("spw.cue.NativeSongCue");
                Constructor<?> ctor=Arrays.stream(pageType.getConstructors()).filter(c -> c.getParameterCount()==6).findFirst().orElseThrow();
                Object diagnostics=type("spw.cue.SongPageDiagnostics").getConstructor().newInstance();
                Object page=ctor.newInstance(song,function("kotlin.jvm.functions.Function0",false),
                    function("kotlin.jvm.functions.Function0",true),diagnostics,null,function("kotlin.jvm.functions.Function2",null));
                wholeSongThroughHostFrame(Files.createDirectory(folder.resolve("whole-song-plugins")),song,page,source,originals);
                Constructor<?> songState=(Constructor<?>)field(song,"songState");
                Object sort=songState.getParameterTypes()[3].getEnumConstants()[0];
                Object lateStore=type("spw.cue.SongVisibility").getConstructor(Path.class)
                    .newInstance(folder.resolve("late-song-visibility").resolve("song-visibility.txt"));
                Object latePage=ctor.newInstance(song,function("kotlin.jvm.functions.Function0",false),
                    function("kotlin.jvm.functions.Function0",true),type("spw.cue.SongPageDiagnostics").getConstructor().newInstance(),
                    lateStore,function("kotlin.jvm.functions.Function2",null));
                verifyAlreadyLoadedSongs(folder,song,latePage,sort,source,lateStore);
                Object grouped=song.getClass().getMethod("filterTracks",List.class,Object.class,boolean.class,String.class)
                    .invoke(song,List.of(source,normal),sort,false,"");
                List<?> artistGroups=(List<?>)song.getClass().getMethod("groupEntities",Object.class).invoke(song,grouped);
                verifyWholeWaveCoverRows(Files.createDirectory(folder.resolve("whole-wave-artwork-plugins")),song,page,artistGroups,source);
                artistStateThroughHostFrame(Files.createDirectory(folder.resolve("artist-plugins")),artist,song,page,artistGroups,source);
                Method project=pageType.getMethod("projectArtistGroups",List.class,boolean.class,Set.class);
                Object expanded=field(project.invoke(page,originals,false,Set.of()),"groups");
                require(displayed(song,expanded).size()==4,"Artist Songs must retain whole audio and show both CUE tracks plus ordinary song");
                Object hidden=field(project.invoke(page,originals,true,Set.of()),"groups");
                require(Boolean.TRUE.equals(field(hidden,"artistPage")),"Artist rows must render without first mounting main Songs");
                List<?> tracks=displayed(song,hidden);
                require(tracks.size()==3,"Hiding whole audio must retain two CUE tracks and ordinary song");
                retainedSongsOrder(hookRegistrar(Files.createDirectory(folder.resolve("songs-plugins"))),
                    page,group,hidden,tracks,normal);
                Method title=type("com.xuncorp.voxzen.data.entity.Track").getMethod("getTitle");
                require(title.invoke(tracks.get(0)).equals("First") && title.invoke(tracks.get(1)).equals("Second"),"CUE titles/order lost");
                Class<?> searchInput=type("spw.cue.ArtistSongSearch");
                Method searchProject=pageType.getMethod("projectArtistSearch",artistSearchType,searchInput,boolean.class,Set.class);
                Constructor<?> query=searchInput.getConstructor(List.class,String.class);
                Object titleMatch=searchProject.invoke(page,search,query.newInstance(List.of(source,normal),"sEcOnD"),false,Set.of());
                List<?> results=displayed(song,field(titleMatch,"groups"));
                require(results.size()==1 && title.invoke(results.getFirst()).equals("Second"),
                    "Artist search must find a CUE-only title and exclude other segments and the whole audio");
                Object ordinaryMatch=searchProject.invoke(page,search,query.newInstance(List.of(source,normal),title.invoke(normal)),true,Set.of());
                require(displayed(song,field(ordinaryMatch,"groups")).equals(List.of(normal)),
                    "Artist search must preserve ordinary native results");
                Method duration=type("com.xuncorp.voxzen.data.entity.Track").getMethod("getDuration");
                require(duration.invoke(tracks.get(0)).equals(10_000L) && duration.invoke(tracks.get(1)).equals(20_000L),"CUE durations regressed");
                Object cueTrack=field(field(((List<?>)field(((List<?>)field(hidden,"entries")).get(0),"rows")).get(0),"song"),"track");
                Object visCompanion=type("spw.cue.SongVisibility").getField("Companion").get(null);
                Method key=Arrays.stream(visCompanion.getClass().getMethods()).filter(m -> m.getName().equals("key")).findFirst().orElseThrow();
                String hiddenKey=(String)key.invoke(visCompanion,cueTrack);
                Object excluded=field(project.invoke(page,originals,true,Set.of(hiddenKey)),"groups");
                require(displayed(song,excluded).size()==2,"Hidden/removed CUE must disappear from Artist Songs");
                Object excludedMatch=searchProject.invoke(page,search,query.newInstance(List.of(source,normal),"First"),true,Set.of(hiddenKey));
                require(displayed(song,field(excludedMatch,"groups")).isEmpty(),"Artist search must respect hidden and removed cues");
                Object clearQuery=searchProject.invoke(page,search,query.newInstance(List.of(source,normal),""),true,Set.of());
                require(displayed(song,field(clearQuery,"groups")).size()==3,"Clearing Artist search must restore all three rows");
                System.out.println("PASS artist search finds CUE-only titles and respects visibility");
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
