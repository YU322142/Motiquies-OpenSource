package com.mapsyncer.server;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone.CaveModeParams;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;

/** A previously published layer becoming empty must send native empty data, not silently vanish. */
public final class EmptyLayerPublication {
 public record Layer(String relativePath,Path outputDir,int regionX,int regionZ,int minY,LightMode mode,CaveModeParams cave){}
 public static void completeRemoved(List<Layer> layers,Map<String,TimestampHashEntry> previous,GenerationCache cache,
                                    long generationTime,boolean conversionFailed)throws IOException{
  try{
   for(Layer layer:layers){
    var before=previous.get(layer.relativePath());
    if(before==null||cache.getMeta(layer.relativePath())!=null)continue;
    if(conversionFailed){cache.update(layer.relativePath(),before.timestampSeconds(),before.hash());continue;}
    // Use the existing native-format serializer. No populated tiles need a block lookup.
    var empty=new MapRegionData(layer.minY(),layer.mode(),layer.cave());
    byte[] raw=XaeroBinaryWriter.serialize(empty,layer.minY(),null);
    var written=XaeroWriter.writeRegionFile(layer.outputDir(),new ConvertedRegion(layer.regionX(),layer.regionZ(),raw));
    cache.update(layer.relativePath(),generationTime,written.crc32Hash());
   }
  }catch(IOException|RuntimeException failure){
   // Keep the knowledge that these layers were published: a later retry must still emit empties.
   for(var entry:previous.entrySet())if(cache.getMeta(entry.getKey())==null){var value=entry.getValue();cache.update(entry.getKey(),value.timestampSeconds(),value.hash());}
   throw failure;
  }
 }
}
