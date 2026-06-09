package dev.vfyjxf.production.example.neoforge2612;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(ProductionNeoForgeExampleMod.MOD_ID)
public final class ProductionNeoForgeExampleMod {
    public static final String MOD_ID = "production_neoforge_2612";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ProductionNeoForgeExampleMod() {
        LOGGER.info("ProductionGradle NeoForge 26.1.2 example loaded.");
        LOGGER.info("Is Production: {}", FMLEnvironment.production);
    }
}
