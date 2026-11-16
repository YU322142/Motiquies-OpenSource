package net.neoforged.fml.loading;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
public final class LoadingModList {
    private static final LoadingModList INSTANCE=new LoadingModList();
    public static boolean jeiPresent=true,available=true;
    public static LoadingModList get(){return available?INSTANCE:null;}
    public ModFileInfo getModFileById(String id){return jeiPresent&&id.equals("jei")?new ModFileInfo():null;}
}
