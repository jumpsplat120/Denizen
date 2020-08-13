package com.denizenscript.denizen.nms.v1_16.impl;

import com.denizenscript.denizen.nms.abstracts.BiomeNMS;
import com.denizenscript.denizen.nms.util.ReflectionHelper;
import net.minecraft.server.v1_16_R2.*;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.v1_16_R2.block.CraftBlock;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BiomeNMSImpl extends BiomeNMS {

    private final BiomeBase biomeBase;

    public BiomeNMSImpl(Biome biome) {
        super(biome);
        this.biomeBase = CraftBlock.biomeToBiomeBase(biome);
    }

    @Override
    public float getHumidity() {
        return biomeBase.getHumidity();
    }

    @Override
    public float getTemperature() {
        return biomeBase.getTemperature();
    }

    @Override
    public List<EntityType> getAmbientEntities() {
        return getSpawnableEntities(EnumCreatureType.AMBIENT);
    }

    @Override
    public List<EntityType> getCreatureEntities() {
        return getSpawnableEntities(EnumCreatureType.CREATURE);
    }

    @Override
    public List<EntityType> getMonsterEntities() {
        return getSpawnableEntities(EnumCreatureType.MONSTER);
    }

    @Override
    public List<EntityType> getWaterEntities() {
        return getSpawnableEntities(EnumCreatureType.WATER_CREATURE);
    }

    @Override
    public void setHumidity(float humidity) {
        ReflectionHelper.setFieldValue(BiomeBase.class, "j", biomeBase, humidity);
    }

    @Override
    public void setTemperature(float temperature) {
        ReflectionHelper.setFieldValue(BiomeBase.class, "i", biomeBase, temperature);
    }

    @Override
    protected boolean getDoesRain() {
        return biomeBase.d() == BiomeBase.Precipitation.RAIN;
    }

    @Override
    protected boolean getDoesSnow() {
        return biomeBase.d() == BiomeBase.Precipitation.SNOW;
    }

    private List<EntityType> getSpawnableEntities(EnumCreatureType creatureType) {
        List<EntityType> entityTypes = new ArrayList<>();
        for (BiomeBase.BiomeMeta meta : biomeBase.getMobs(creatureType)) {
            // TODO: check if this works
            try {
                String n = EntityTypes.getName(meta.c).getKey();
                EntityType et = EntityType.fromName(n);
                if (et == null) {
                    et = EntityType.valueOf(n.toUpperCase(Locale.ENGLISH));
                }
                entityTypes.add(et);
            }
            catch (Throwable e) {
                // Ignore the error. Likely from invalid entity type name output.
            }
        }
        return entityTypes;
    }
}
