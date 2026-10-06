package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NbtSectionSerializerTest {
    private static RegistryAccess registries;
    @BeforeAll static void setup() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "nbt-tests"));
        }
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(h -> biomes.register(h.key(), h.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        registries = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze()));
    }

    private static CompoundTag stoneSection(int y) {
        var section = new CompoundTag(); section.putByte("Y", (byte)y);
        var states = new CompoundTag(); var palette = new ListTag();
        var stone = new CompoundTag(); stone.putString("Name", "minecraft:stone");
        palette.add(stone); states.put("palette", palette); section.put("block_states", states);
        return section;
    }
    private static CompoundTag chunk(int minY, int height, int sectionY, int surfaceValue) {
        var tag = new CompoundTag(); tag.putString("Status", "minecraft:full");
        tag.putInt("yPos", minY >> 4);
        // Vanilla stores dimension height externally, not in a custom Height tag.
        var sections = new ListTag(); sections.add(stoneSection(sectionY));tag.put("sections", sections);
        var packed = new SimpleBitStorage(32 - Integer.numberOfLeadingZeros(height), 256);
        for (int i=0;i<256;i++) packed.set(i, surfaceValue);
        var maps = new CompoundTag();maps.putLongArray("WORLD_SURFACE", packed.getRaw());tag.put("Heightmaps", maps);
        return tag;
    }
    private static boolean complete(CompoundTag tag, int minY, int height) {
        var result = NbtSectionSerializer.serializeTag(registries,minY,height,1000,1000,Optional.of(tag));
        assertNotNull(result);return result.completeColumn();
    }
    @Test void paddedVanillaHeightmapAllowsAnExistingSurface() {
        // Stone section Y=4 ends at y=79; heightmap stores y+1 relative to minY=-64.
        assertTrue(complete(chunk(-64,384,4,144),-64,384));
    }
    @Test void normalLightOnlyBoundarySectionsDoNotMakeColumnIncomplete() {
        var tag=chunk(-64,384,19,384);var sections=tag.getList("sections",10);
        for (int y : new int[]{-5,20}) {
            var light=new CompoundTag();light.putByte("Y",(byte)y);
            byte[] data=new byte[2048];java.util.Arrays.fill(data,(byte)255);
            light.putByteArray("SkyLight",data);sections.add(light);
        }
        assertTrue(complete(tag,-64,384));
    }
    @Test void dimensionHeightControlsHeightmapDecoding() {
        assertTrue(complete(chunk(0,256,3,64),0,256));
        assertTrue(complete(chunk(-128,512,5,224),-128,512));
    }
    @Test void staleHeightmapAboveActualTerrainDoesNotRejectFullChunk() {
        assertTrue(complete(chunk(-64,384,0,144),-64,384));
        assertTrue(complete(chunk(-64,2096,0,2064),-64,2096));
    }
    @Test void outOfRangeBlockSectionIsNotSilentlyIgnored() {
        var tag=chunk(-64,384,19,384);tag.getList("sections",10).add(stoneSection(20));
        assertFalse(complete(tag,-64,384));
    }
    @Test void truncatedHeightmapDoesNotOverrideValidSectionData() {
        var tag=chunk(-64,384,19,384);
        tag.getCompound("Heightmaps").putLongArray("WORLD_SURFACE",new long[]{0});
        assertTrue(complete(tag,-64,384));
    }
    @Test void invalidHeightValueDoesNotOverrideValidSectionData() {
        assertTrue(complete(chunk(-64,384,19,511),-64,384));
    }
    @Test void missingHeightmapsDoNotOverrideValidSectionData() {
        var tag=chunk(-64,384,4,144);tag.remove("Heightmaps");
        assertTrue(complete(tag,-64,384));
    }
    @Test void malformedBlockSectionStillRejectsColumn() {
        var tag=chunk(-64,384,4,144);
        tag.getList("sections",10).getCompound(0).put("block_states",new CompoundTag());
        assertFalse(complete(tag,-64,384));
    }
    @Test void protoChunkStillRequiresGeneration() {
        var tag=chunk(-64,384,4,144);tag.putString("Status","minecraft:noise");
        assertNull(NbtSectionSerializer.serializeTag(registries,-64,384,1,2,Optional.of(tag)));
    }
    @Test void invalidSectionTagTypesStillRejectColumn() {
        var tag=chunk(-64,384,4,144);
        tag.getList("sections",10).getCompound(0).putString("block_states","invalid");
        assertFalse(complete(tag,-64,384));
        tag=chunk(-64,384,4,144);
        tag.getList("sections",10).getCompound(0).putString("Y","invalid");
        assertFalse(complete(tag,-64,384));
    }
    @Test void tallDimensionCountDoesNotOverwriteTheFirstSection() {
        var tag=chunk(-64,2096,0,2064);var sections=new ListTag();
        for(int y=-4;y<127;y++) sections.add(stoneSection(y));
        tag.put("sections",sections);
        var result=NbtSectionSerializer.serializeTag(registries,-64,2096,1,2,Optional.of(tag));
        assertNotNull(result);assertTrue(result.completeColumn());
        assertEquals(131,result.sectionYs().length);
        var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(result.sectionBytes()));
        try {
            assertEquals(131,buf.readVarInt());
            int[] lengths=result.sectionLengths();
            for(int y=-4;y<127;y++) {
                int start=buf.readerIndex();
                assertEquals(y,buf.readByte());
                assertEquals(4096,buf.readShort());
                buf.readerIndex(start+lengths[y+4]);
            }
            assertEquals(0,buf.readableBytes());
        } finally { buf.release(); }
    }
    @Test void liveCompletenessUsesActualNonAirSections() {
        var section=new LevelChunkSection(registries.registryOrThrow(Registries.BIOME));
        section.setBlockState(0,0,0,Blocks.STONE.defaultBlockState());
        LevelChunkSection[] sections={null,section};
        assertTrue(SectionSerializer.isCompleteColumn(sections,-4,-3));
        assertFalse(SectionSerializer.isCompleteColumn(sections,-4,-4));
        assertTrue(SectionSerializer.isCompleteColumn(new LevelChunkSection[2],-4,Integer.MIN_VALUE));
    }
    @Test void validEmptyColumnRemainsComplete() {
        var tag=chunk(-64,384,0,0);var sections=new ListTag();
        var section=new CompoundTag();section.putByte("Y",(byte)0);sections.add(section);tag.put("sections",sections);
        assertTrue(complete(tag,-64,384));
    }
}
