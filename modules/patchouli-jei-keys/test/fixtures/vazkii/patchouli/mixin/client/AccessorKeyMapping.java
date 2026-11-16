package vazkii.patchouli.mixin.client;
import java.util.*;
import net.minecraft.client.KeyMapping;
/** Reproduces the actual startup condition: JEI key mappings are not registered yet. */
public interface AccessorKeyMapping {
    static Map<String,KeyMapping> getAllKeyMappings() { return Map.of(); }
}
