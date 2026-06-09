package dev.vfyjxf.production.example.forge1211;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(ProductionForgeExampleMod.MOD_ID)
public final class ProductionForgeExampleMod {
    public static final String MOD_ID = "production_forge_1211";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ProductionForgeExampleMod() {
        LOGGER.info("ProductionGradle Forge 1.21.1 example loaded.");
    }
}
