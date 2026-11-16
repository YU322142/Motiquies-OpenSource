package org.motiquies.client;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
/** Marks only one native-derived texture file outdated. Source ZIP and personal data are never touched. */
final class ColdMapCache {
 static void invalidate(Path source,Path cache,int x,int z)throws IOException{
  source=source.toAbsolutePath().normalize();cache=cache.toAbsolutePath().normalize();
  String key=x+"_"+z;
  if(!source.getFileName().toString().equals(key+".zip")||!cache.getFileName().toString().equals(key+".xwmc")
    ||cache.getParent()==null||!cache.getParent().getFileName().toString().matches("cache_\\d+")
    ||!cache.getParent().getParent().equals(source.getParent()))throw new IOException("Native texture/source boundary mismatch");
  if(Files.isSymbolicLink(source.getParent())||Files.isSymbolicLink(cache.getParent()))throw new IOException("Linked texture directory");
  BasicFileAttributes attributes;
  try{attributes=Files.readAttributes(cache,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
  catch(NoSuchFileException absent){return;}
  if(!attributes.isRegularFile()||attributes.isSymbolicLink())throw new IOException("Native texture is not a regular file");
  Path outdated=cache.resolveSibling(key+".xwmc.outdated");
  if(Files.isSymbolicLink(outdated)||Files.isDirectory(outdated,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid outdated texture path");
  try{Files.move(cache,outdated,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
  catch(AtomicMoveNotSupportedException unsupported){Files.move(cache,outdated,StandardCopyOption.REPLACE_EXISTING);}
 }
}
