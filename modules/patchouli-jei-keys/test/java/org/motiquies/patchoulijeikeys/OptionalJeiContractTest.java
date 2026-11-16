package org.motiquies.patchoulijeikeys;
import net.neoforged.fml.loading.LoadingModList;
public final class OptionalJeiContractTest {
    public static void main(String[] args){
        var plugin=new PatchouliJeiMixinPlugin();
        LoadingModList.jeiPresent=false;
        if(plugin.shouldApplyMixin("target","mixin"))throw new AssertionError("JEI absent must skip compatibility");
        LoadingModList.jeiPresent=true;
        if(!plugin.shouldApplyMixin("target","mixin"))throw new AssertionError("Present pinned JEI must enable compatibility");
        LoadingModList.available=false;
        try{plugin.shouldApplyMixin("target","mixin");throw new AssertionError("Unexpected loader failure was swallowed");}
        catch(IllegalStateException expected){}
        System.out.println("PASS: JEI presence gate and unexpected loader error propagation");
    }
}
