package com.mapsyncer.server;
import java.nio.file.*;
import java.util.*;
public final class PostSaveRegionsTest {
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]).toAbsolutePath();
  var q=new DirtyRegionQueue(16,3,30,5);
  var router=new PostSaveRegions(q,Map.of(root.resolve("world/region"),"minecraft:overworld",root.resolve("world/DIM-1/region"),"minecraft:the_nether"));
  check(router.saved(root.resolve("world/region/r.-1.-2.mca"),-1,-33,1),"native committed region routed");
  check(q.size()==1&&!Files.exists(root),"callback only records memory, no IO");
  check(!router.saved(root.resolve("private-input/r.-1.-2.mca"),-1,-33,1),"private conversion never loops back");
  check(!router.saved(root.resolve("world/poi/r.-1.-2.mca"),-1,-33,1),"POI is not terrain");
  check(!router.saved(root.resolve("world/region/r.0.0.mca"),-1,-33,1),"file/chunk mismatch rejected");
  check(router.saved(root.resolve("world/DIM-1/region/r.-1.-2.mca"),-1,-33,2)&&q.size()==2,"dimension separation");
  try{router.getClass().getMethod("register",Path.class,String.class).invoke(router,root.resolve("new-dimension/region"),"example:new");}
  catch(ReflectiveOperationException missing){throw new AssertionError("runtime dimension registration must be available before first region directory exists",missing);}
  check(router.saved(root.resolve("new-dimension/region/r.0.0.mca"),0,0,2)&&q.size()==3&&!Files.exists(root),"dynamic first-save route is memory only");
  q.close();check(!router.saved(root.resolve("world/region/r.0.0.mca"),0,0,3),"closed lifecycle cannot receive writes");
  SavedRegionLifecycleTest.run(root.resolve("lifecycle"));
  DirtyRegionReplayTest.run(root.resolve("replay"));
  System.out.println("PASS post-save routes: native terrain only, no IO, negative coordinates, temp/POI exclusion, lifecycle");
 }
 static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
}
