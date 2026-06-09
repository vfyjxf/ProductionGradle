package dev.vfyjxf.production.example;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(ProductionExampleMod.MOD_ID)
public final class ProductionExampleMod {
    public static final String MOD_ID = "production_example";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ProductionExampleMod() {
        LOGGER.info("ProductionGradle ModDevGradle example loaded.");
        LOGGER.info("Is Production: {}", FMLEnvironment.production);
    }
}
