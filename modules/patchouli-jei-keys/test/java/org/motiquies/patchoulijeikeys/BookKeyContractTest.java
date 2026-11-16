package org.motiquies.patchoulijeikeys;

import java.lang.reflect.*;
import java.util.*;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.*;
import mezz.jei.api.runtime.*;
import net.minecraft.world.item.ItemStack;

/** Executes original or actually Mixin-transformed Patchouli bytes against real JEI API interfaces. */
public final class BookKeyContractTest {
    private static void require(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    @SuppressWarnings("unchecked")
    private static <T>T proxy(Class<T> api,InvocationHandler action){return (T)Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},action);}
    private static Object objectMethod(Object object,Method method,Object[] args) {
        return switch(method.getName()){case "toString"->"JEI boundary fixture";case "hashCode"->System.identityHashCode(object);case "equals"->object==args[0];default->throw new AssertionError(method);};
    }
    private static final class Session {
        int recipe=82,uses=85,keyLookups,shown;
        boolean active=true,unbound,broken;
        InputConstants.Type recipeType=InputConstants.Type.KEYSYM;
        RecipeIngredientRole role;
        ItemStack hovered;
        final IJeiRuntime runtime;
        Session() {
            IJeiKeyMapping recipeKey=key(true),useKey=key(false);
            IJeiKeyMappings mappings=proxy(IJeiKeyMappings.class,(p,m,a)->switch(m.getName()){
                case "getShowRecipe"->recipeKey;case "getShowUses"->useKey;default->objectMethod(p,m,a);});
            IFocusFactory factory=proxy(IFocusFactory.class,(p,m,a)->{
                if(!m.getName().equals("createFocus"))return objectMethod(p,m,a);
                require(a.length==3,"Unexpected focus factory overload");
                role=(RecipeIngredientRole)a[0];hovered=(ItemStack)a[2];
                return proxy(IFocus.class,(f,fm,fa)->fm.getName().equals("getRole")?role:objectMethod(f,fm,fa));
            });
            IJeiHelpers helpers=proxy(IJeiHelpers.class,(p,m,a)->m.getName().equals("getFocusFactory")?factory:objectMethod(p,m,a));
            IRecipesGui gui=proxy(IRecipesGui.class,(p,m,a)->{
                if(!m.getName().equals("show"))return objectMethod(p,m,a);
                require(a.length==1&&a[0] instanceof IFocus,"Expected exactly the hovered-item focus");shown++;return null;
            });
            runtime=proxy(IJeiRuntime.class,(p,m,a)->switch(m.getName()){
                case "getKeyMappings"->{keyLookups++;if(broken)throw new IllegalStateException("genuine-runtime-failure");yield mappings;}
                case "getJeiHelpers"->helpers;case "getRecipesGui"->gui;default->objectMethod(p,m,a);
            });
        }
        IJeiKeyMapping key(boolean output) {
            return proxy(IJeiKeyMapping.class,(p,m,a)->switch(m.getName()){
                case "isActiveAndMatches"->{InputConstants.Key key=(InputConstants.Key)a[0];yield active&&!unbound&&key.getType()==(output?recipeType:InputConstants.Type.KEYSYM)&&key.getValue()==(output?recipe:uses);}
                case "isUnbound"->unbound;default->objectMethod(p,m,a);
            });
        }
    }
    public static void main(String[] args)throws Exception{
        Class<?> pluginClass=Class.forName("vazkii.patchouli.client.jei.PatchouliJeiPlugin");
        Object plugin=pluginClass.getConstructor().newInstance();
        Method key=pluginClass.getMethod("handleRecipeKeybind",int.class,int.class,ItemStack.class);
        Method available=pluginClass.getMethod("onRuntimeAvailable",IJeiRuntime.class);
        Method unavailable=pluginClass.getMethod("onRuntimeUnavailable");
        ItemStack hovered=new ItemStack("actual-hovered-boundary");
        require(!(boolean)key.invoke(null,82,0,hovered),"Before JEI runtime readiness, input must remain unhandled");
        Session first=new Session();available.invoke(plugin,first.runtime);
        require((boolean)key.invoke(null,82,0,hovered),"Runtime-ready recipe lookup was not handled after early missing key mappings");
        require(first.shown==1&&first.role==RecipeIngredientRole.OUTPUT&&first.hovered==hovered,"Recipe lookup must open once for the original hovered item");
        require((boolean)key.invoke(null,85,0,hovered),"Usage lookup not handled");
        require(first.shown==2&&first.role==RecipeIngredientRole.INPUT,"Usage lookup must open once with INPUT role");
        require(!(boolean)key.invoke(null,90,0,hovered)&&first.shown==2,"Unrelated key was consumed or opened a duplicate GUI");
        first.recipe=80;
        require(!(boolean)key.invoke(null,82,0,hovered),"Old binding must stop matching after native runtime rebinding");
        require((boolean)key.invoke(null,80,0,hovered)&&first.shown==3,"Current configured binding must be resolved lazily");
        first.recipe=73;first.recipeType=InputConstants.Type.SCANCODE;
        require(!(boolean)key.invoke(null,73,0,hovered),"A keysym must not masquerade as a configured scancode");
        require((boolean)key.invoke(null,-1,73,hovered)&&first.shown==4,"Native scancode binding must receive the actual converted key");
        first.recipe=80;first.recipeType=InputConstants.Type.KEYSYM;first.uses=80;
        require((boolean)key.invoke(null,80,0,hovered)&&first.shown==5&&first.role==RecipeIngredientRole.OUTPUT,"Matching both mappings must preserve recipe-first priority and open only once");
        first.uses=85;
        first.active=false;require(!(boolean)key.invoke(null,80,0,hovered)&&first.shown==5,"Inactive native key context must remain unhandled");
        first.active=true;first.unbound=true;require(!(boolean)key.invoke(null,80,0,hovered)&&first.shown==5,"Unbound native shortcut must remain unhandled");
        first.unbound=false;first.broken=true;
        try{key.invoke(null,80,0,hovered);throw new AssertionError("Genuine runtime failure was swallowed");}
        catch(InvocationTargetException expected){require(expected.getCause() instanceof IllegalStateException&&expected.getCause().getMessage().equals("genuine-runtime-failure"),"Unexpected exception changed");}
        first.broken=false;unavailable.invoke(plugin);int reads=first.keyLookups;
        require(!(boolean)key.invoke(null,80,0,hovered)&&first.keyLookups==reads,"Unavailable JEI must not retain or access the obsolete runtime");
        Session second=new Session();second.recipe=79;available.invoke(plugin,second.runtime);
        require(!(boolean)key.invoke(null,80,0,hovered),"Previous runtime binding leaked after restart");
        require((boolean)key.invoke(null,79,0,hovered)&&second.shown==1,"Replacement runtime must handle its own current binding once");
        System.out.println("PASS: real Patchouli handler; runtime readiness/removal/replacement, recipe/use roles, rebinding/scancodes, priority, inactive/unbound keys, unrelated input, exceptions, single GUI invocation");
    }
}
