package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;

/**
 * The prediction terrain program, packed-quad renderer: one raw GL
 * program whose vertex stage expands packed 48-byte quads from a texture
 * buffer using {@code gl_VertexID} (four corners per quad, no vertex
 * attributes at all).  Terrain and fluids share the fragment path — the
 * opaque pass forces alpha to one through {@link #setOpaqueAlpha(float)},
 * the translucent fluid pass re-draws the fluid ranges with blending on and
 * lets the sprite's own alpha (water ships ~0.7 in the atlas) show the depth
 * behind it, exactly like the reference LOD renderer.
 */
final class PredictionTerrainProgram implements AutoCloseable {
    private final GlProgram program;
    private final int modelView;
    private final int projection;
    private final int tileOffset;
    private final int spacing;
    private final int cellAxis;
    private final int quads;
    private final int yield;
    private final int atlas;
    private final int lightmap;
    private final int spriteRects;
    private final int vanillaMaskSampler;
    private final int vanillaMaskOrigin;
    private final int vanillaMaskSize;
    private final int vanillaRenderDistance;
    private final int mainDepth;
    private final int mainDepthPlanes;
    private final int depthBias;
    private final int colorModulator;
    private final int fogColor;
    private final int fogStart;
    private final int fogEnd;
    private final int fogDensity;
    private final int hazeStart;
    private final int lodColorScale;
    private final int lightEnabled;
    private final int useAverage;
    private final int opaqueAlpha;
    private final int directionalTint;
    private final int sharedFog;
    private final int sharedFogColor;
    private final float[] faceTints = new float[7];

    private PredictionTerrainProgram() {
        this(TERRAIN_VERTEX, TERRAIN_FRAGMENT);
    }

    private PredictionTerrainProgram(String vertex, String fragment) {
        this.program = GlProgram.link("vss_prediction_terrain",
                vertex, fragment);
        this.modelView = program.uniform("ModelViewMat");
        this.projection = program.uniform("ProjMat");
        this.tileOffset = program.uniform("TileOffset");
        this.spacing = program.uniform("Spacing");
        this.cellAxis = program.uniform("CellAxis");
        this.quads = program.uniform("QuadPayload");
        this.yield = program.uniform("Yield");
        this.atlas = program.uniform("Atlas");
        this.lightmap = program.uniform("Lightmap");
        this.spriteRects = program.uniform("SpriteTable");
        this.vanillaMaskSampler = program.uniform("VanillaMask");
        this.vanillaMaskOrigin = program.uniform("VanillaMaskOrigin");
        this.vanillaMaskSize = program.uniform("VanillaMaskSize");
        this.vanillaRenderDistance = program.uniform("VanillaRenderDistance");
        this.mainDepth = program.uniform("MainDepth");
        this.mainDepthPlanes = program.uniform("MainDepthPlanes");
        this.depthBias = program.uniform("DepthBias");
        this.colorModulator = program.uniform("ColorModulator");
        this.fogColor = program.uniform("LodFogColor");
        this.fogStart = program.uniform("LodFogStart");
        this.fogEnd = program.uniform("LodFogEnd");
        this.fogDensity = program.uniform("LodFogDensity");
        this.hazeStart = program.uniform("LodHazeStart");
        this.lodColorScale = program.uniform("LodColorScale");
        this.lightEnabled = program.uniform("LodLightmap");
        this.useAverage = program.uniform("UseAverage");
        this.opaqueAlpha = program.uniform("OpaqueAlpha");
        this.directionalTint = program.uniform("DirectionalTint[0]");
        this.sharedFog = program.uniform("VssPredictionFog");
        this.sharedFogColor = program.uniform("VssPredictionFogColor");
    }

    static PredictionTerrainProgram create() {
        return new PredictionTerrainProgram();
    }

    static PredictionTerrainProgram createIris(String taa, java.util.function.UnaryOperator<String> patch) {
        return new PredictionTerrainProgram(irisVertex(taa), patch.apply(irisFragment()));
    }

    static String irisVertex(String taa) {
        String source = TERRAIN_VERTEX.replace("#version 150", "#version 460 core\n#define VSS_IRIS");
        return source.replace("uniform usamplerBuffer", taa + "\nuniform usamplerBuffer");
    }

    static String irisFragment() {
        return TERRAIN_FRAGMENT.replace("#version 150", "#version 460 core\n#define VSS_IRIS");
    }

    void setIrisFrame(Matrix4f inverseProjection, int width, int height, boolean zeroToOne, int[] ids) {
        setIrisFrame(inverseProjection, width, height, zeroToOne, ids, 0.0F);
    }

    void setIrisFrame(Matrix4f inverseProjection, int width, int height, boolean zeroToOne, int[] ids, float clearDepth) {
        GL20.glUniformMatrix4fv(program.uniform("VssInverseProjection"), false, inverseProjection.get(new float[16]));
        GL20.glUniform2f(program.uniform("VssViewport"), width, height);
        GL20.glUniform1i(program.uniform("VssZeroToOne"), zeroToOne ? 1 : 0);
        GL20.glUniform1f(program.uniform("VssClearDepth"), clearDepth);
        org.lwjgl.opengl.GL30.glUniform1uiv(program.uniform("VssMaterialIds[0]"), ids);
    }

    void use() {
        program.use();
    }

    void setCamera(Matrix4f modelView, Matrix4f projection) {
        GL20.glUniformMatrix4fv(this.modelView, false, modelView.get(new float[16]));
        GL20.glUniformMatrix4fv(this.projection, false, projection.get(new float[16]));
    }

    void setTile(float offsetX, float offsetY, float offsetZ, float spacing, int cellAxis,
                 boolean useAverage) {
        GL20.glUniform3f(tileOffset, offsetX, offsetY, offsetZ);
        GL20.glUniform1f(this.spacing, spacing);
        GL20.glUniform1i(this.cellAxis, cellAxis);
        GL20.glUniform1i(this.useAverage, useAverage ? 1 : 0);
    }

    void setSamplers(int atlasUnit, int lightmapUnit, int spriteRectUnit, int yieldUnit,
                     int quadUnit) {
        GL20.glUniform1i(atlas, atlasUnit);
        GL20.glUniform1i(lightmap, lightmapUnit);
        GL20.glUniform1i(spriteRects, spriteRectUnit);
        GL20.glUniform1i(yield, yieldUnit);
        GL20.glUniform1i(quads, quadUnit);
        GL20.glUniform1i(vanillaMaskSampler, 6);
    }

    void bindVanillaMask(PredictionVanillaMask mask, net.minecraft.world.phys.Vec3 camera) {
        mask.bind(6, camera, vanillaMaskOrigin, vanillaMaskSize);
        GL20.glUniform1f(vanillaRenderDistance, mask.renderDistanceBlocks());
    }

    void bindMainDepth(int texture, VssLodProjection.MatrixData projection) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE7);
        RenderSystem.bindTexture(texture);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(mainDepth, 7);
        GL20.glUniform2f(mainDepthPlanes, projection.vanillaA(), projection.vanillaB());
        GL20.glUniform1f(depthBias, PredictionRenderTarget.DEPTH_BIAS_BLOCKS);
        GL20.glUniform1i(program.uniform("VoxyDepthAvailable"), 0);
        GL20.glUniform1i(program.uniform("VoxyDepth"), 5);
    }

    void bindVoxyDepth(PredictionVoxyDepth.Frame frame, Matrix4f mainMvp) {
        GL20.glUniform1i(program.uniform("VoxyDepthAvailable"), frame == null ? 0 : 1);
        if (frame == null) return;
        RenderSystem.activeTexture(GL13.GL_TEXTURE5);
        RenderSystem.bindTexture(frame.texture());
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, frame.texture());
        // Recover the main projection's clip W from Voxy's original depth.
        // Using both homogeneous rows preserves view bob and camera rotation.
        org.joml.Vector4f numerator = new Matrix4f(mainMvp).mul(frame.inverseMvp()).getRow(3, new org.joml.Vector4f());
        org.joml.Vector4f denominator = frame.inverseMvp().getRow(3, new org.joml.Vector4f());
        GL20.glUniform4f(program.uniform("VoxyDistanceNumerator"), numerator.x, numerator.y, numerator.z, numerator.w);
        GL20.glUniform4f(program.uniform("VoxyDistanceDenominator"), denominator.x, denominator.y, denominator.z, denominator.w);

    }

    void setFrame(float[] fogColor, float start, float end, float density,
                  float hazeFloor, boolean lightOn, float colorScale) {
        GL20.glUniform4f(this.fogColor, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        GL20.glUniform1f(fogStart, start);
        GL20.glUniform1f(fogEnd, end);
        GL20.glUniform1f(fogDensity, density);
        GL20.glUniform1f(hazeStart, hazeFloor);
        GL20.glUniform4f(sharedFog, start, end, hazeFloor, density);
        GL20.glUniform3f(sharedFogColor, fogColor[0], fogColor[1], fogColor[2]);
        GL20.glUniform1f(lodColorScale, colorScale);
        GL20.glUniform1f(lightEnabled, lightOn ? 1.0F : 0.0F);
        GL20.glUniform4f(colorModulator, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    void setDirectionalLighting(ClientLevel level) {
        for (Direction face : Direction.values()) {
            faceTints[face.get3DDataValue()] = level.getShade(face, true);
        }
        faceTints[6] = level.getShade(Direction.UP, false);
        GL20.glUniform1fv(directionalTint, faceTints);
    }

    /**
     * Floor for the written alpha: one for the opaque pass, zero for the
     * translucent fluid pass where the sprite's own alpha rules.
     */
    void setOpaqueAlpha(float alpha) {
        GL20.glUniform1f(opaqueAlpha, alpha);
    }

    @Override
    public void close() {
        program.close();
    }

    private static final String TERRAIN_VERTEX = """
            #version 150
            uniform usamplerBuffer QuadPayload;
            uniform sampler2D SpriteTable;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform vec3 TileOffset;
            uniform float Spacing;
            uniform int CellAxis;
            uniform float DirectionalTint[7];
            flat out float vSprite;
            out vec4 vColor;
            out float vSkyLight;
            flat out float vModelUv;
            flat out float vCutout;
            flat out float vWater;
             out vec2 tileUv;
             out vec2 localXZ;
             out vec3 relative;
            out float vDistance;
            flat out uint vCell;
            flat out float vCellLocal;
            flat out uint vCoverageAxis;
            flat out vec3 vFaceNormal;
            flat out float vSurfaceVisible;
            flat out uint vRealBoundary;

            // One packed quad spans three RGBA32UI texels: texel A holds the
            // x/z words, texel B the y words, attributes and tint, and texel C
            // the source cell used by non-merged geometry at coverage seams.
            // Each 16-bit half belongs to one corner, low half first.
            float halfX(uvec4 texel, int corner) {
                uint word = corner < 2 ? texel.x : texel.y;
                return float((corner & 1) == 0 ? (word & 0xFFFFu) : (word >> 16u));
            }

            float halfZ(uvec4 texel, int corner) {
                uint word = corner < 2 ? texel.z : texel.w;
                return float((corner & 1) == 0 ? (word & 0xFFFFu) : (word >> 16u));
            }

            float half16(uvec4 texel, int corner) {
                uint word = corner < 2 ? texel.x : texel.y;
                return float((corner & 1) == 0 ? (word & 0xFFFFu) : (word >> 16u));
            }

            void main() {
                int quad = gl_VertexID >> 2;
                int corner = gl_VertexID & 3;
                uvec4 texelA = texelFetch(QuadPayload, quad * 3);
                uvec4 texelB = texelFetch(QuadPayload, quad * 3 + 1);
                uvec4 texelC = texelFetch(QuadPayload, quad * 3 + 2);
                uint attr = texelB.z;
                vCell = texelC.x;
                vRealBoundary = (texelC.y >> 25u) & 3u;
                vCellLocal = (texelC.y & 0x01000000u) == 0u ? 1.0 : 0.0;
                bool fineCoordinates = (attr & (1u << 20)) != 0u;
                uint fluid = (attr >> 22) & 3u;
                bool fluidFineY = fluid != 0u && (attr & (1u << 21)) != 0u;
                float horizontalScale = fineCoordinates ? 16.0 : 1.0 / exp2(float((attr >> 16u) & 15u));
                vec3 local = vec3(halfX(texelA, corner) / horizontalScale,
                        (half16(texelB, corner) - 32768.0) / (fineCoordinates || fluidFineY ? 16.0 : 4.0),
                        halfZ(texelA, corner) / horizontalScale);
                uint sprite = attr & 0xFFFFu;
                uint axis = (attr >> 24) & 3u;
                vCoverageAxis = axis;
                bool positive = (attr & (1u << 30)) != 0u;
                bool down = fluid == 0u && (attr & (1u << 29)) != 0u;
                bool modelUv = fluid == 0u && (attr & (1u << 21)) != 0u;
                vModelUv = modelUv ? 1.0 : 0.0;
                bool unshaded = (attr & (1u << 27)) != 0u;
                bool diagonal = unshaded && !modelUv;
                int face = unshaded ? 6 : axis == 0u ? (down ? 0 : 1)
                        : axis == 1u ? (positive ? 5 : 4) : (positive ? 3 : 2);
                vec3 materialPosition = local;
                // Keep voxel tops and their connecting walls at sampled heights.
                // Independent face deltas tear these shared edges apart, even
                // with a one-block clamp. Coverage masks perform LOD handover.
                bool uvYPos = (attr & (1u << 28)) != 0u;
                // Regular X-facing walls use Z as U and regular Z-facing
                // walls use X as U. Grass/flower crosses are diagonal, so
                // their U axis is the varying world coordinate of the plane;
                // using the constant perpendicular coordinate turns the
                // cutout into a one-pixel vertical stripe.
                tileUv = axis == 0u ? materialPosition.xz
                       : diagonal
                       ? (axis == 1u
                           ? vec2(materialPosition.x, uvYPos ? materialPosition.y : -materialPosition.y)
                           : vec2(materialPosition.z, uvYPos ? materialPosition.y : -materialPosition.y))
                       : (axis == 1u
                           ? vec2(materialPosition.z, uvYPos ? materialPosition.y : -materialPosition.y)
                           : vec2(materialPosition.x, uvYPos ? materialPosition.y : -materialPosition.y));
                // Geometry spacing never changes block texture scale. Tile
                // origins are whole blocks, so these UVs also match adjacent LODs.
                if (modelUv && int(sprite) < textureSize(SpriteTable, 0).x) {
                    vec4 uv = texelFetch(SpriteTable, ivec2(int(sprite), 2 + corner / 2), 0);
                    tileUv = (corner & 1) == 0 ? uv.xy : uv.zw;
                }
                localXZ = local.xz;
             relative = local + TileOffset;
                vDistance = length(relative.xz);
                vSprite = float(sprite);
                uint cornerColor = corner == 0 ? texelB.w
                        : corner == 1 ? texelC.y : corner == 2 ? texelC.z : texelC.w;
                vSkyLight = 15.0 - float(cornerColor >> 28u);
                vColor = vec4(
                        float((cornerColor >> 16) & 255u) / 255.0,
                        float((cornerColor >> 8) & 255u) / 255.0,
                        float(cornerColor & 255u) / 255.0,
                        DirectionalTint[face]);
                vCutout = (attr & (1u << 26)) != 0u ? 1.0 : 0.0;
                vWater = float(fluid);
                gl_Position = ProjMat * ModelViewMat * vec4(relative, 1.0);
                #ifdef VSS_IRIS
                gl_Position.xy += vssTaaShift() * gl_Position.w;
                #endif
                // The triangle stream has mixed winding. Cull using its
                // explicit outward normal instead, per face, not per tile.
                // Crossed plants stay double-sided; terrain has no underside.
                vec3 faceNormal = axis == 0u ? vec3(0.0, 1.0, 0.0)
                        : axis == 1u ? vec3(positive ? 1.0 : -1.0, 0.0, 0.0)
                        : vec3(0.0, 0.0, positive ? 1.0 : -1.0);
                vFaceNormal = diagonal ? vec3(0.0) : faceNormal;
                // Keep the real position for every vertex. Moving only some
                // vertices outside clip space makes the rasterizer create a
                // new clipping edge across an otherwise planar quad, which
                // shows up as a thin see-through strip at the Voxy handoff.
                bool surfaceVisible = !down
                        && (diagonal || dot(faceNormal, relative) < 0.0);
                vSurfaceVisible = surfaceVisible ? 1.0 : 0.0;
            }
            """;

    private static final String TERRAIN_FRAGMENT = """
            #version 150
            uniform sampler2D Atlas;
            uniform sampler2D Lightmap;
             uniform sampler2D SpriteTable;
             uniform sampler2D Yield;
            uniform sampler3D VanillaMask;
            uniform vec3 VanillaMaskOrigin;
            uniform ivec3 VanillaMaskSize;
            uniform float VanillaRenderDistance;
            uniform sampler2D MainDepth;
            uniform vec2 MainDepthPlanes;
            uniform float DepthBias;
            """ + PredictionFogBridge.GLSL + """
            #ifndef VSS_IRIS
            uniform sampler2D VoxyDepth;
            uniform bool VoxyDepthAvailable;
            uniform vec4 VoxyDistanceNumerator;
            uniform vec4 VoxyDistanceDenominator;
            #endif
            uniform vec4 ColorModulator;
            uniform vec4 LodFogColor;
            uniform float LodFogStart;
            uniform float LodFogEnd;
            uniform float LodFogDensity;
            uniform float LodHazeStart;
            uniform float LodColorScale;
            uniform float LodLightmap;
            uniform int UseAverage;
            uniform float Spacing;
            uniform int CellAxis;
            uniform float OpaqueAlpha;
            flat in float vSprite;
            in vec4 vColor;
            flat in float vCutout;
            flat in float vWater;
            in float vSkyLight;
            flat in float vModelUv;
             flat in uint vCell;
             flat in float vCellLocal;
            flat in uint vCoverageAxis;
            flat in vec3 vFaceNormal;
            flat in float vSurfaceVisible;
            flat in uint vRealBoundary;
             in vec2 tileUv;
             in vec2 localXZ;
             in vec3 relative;
            in float vDistance;
            #ifdef VSS_IRIS
            struct VoxyFragmentParameters {
                vec4 sampledColour; vec2 tile; vec2 uv; uint face; uint modelId;
                vec2 lightMap; vec4 tinting; uint customId;
            };
            void voxy_emitFragment(VoxyFragmentParameters parameters);
            uniform uint VssMaterialIds[256];
            uniform mat4 VssInverseProjection;
            uniform vec2 VssViewport;
            uniform bool VssZeroToOne;
            uniform float VssClearDepth;
            #else
            out vec4 fragColor;
            #endif

             vec2 spriteUv(vec2 rectMin, vec2 rectMax, vec2 uv, vec2 uvDx, vec2 uvDy) {
                 vec2 size = rectMax - rectMin;
                 vec2 pixels = max(size * vec2(textureSize(Atlas, 0)), vec2(1.0));
                 float rho = max(length(uvDx * pixels), length(uvDy * pixels));
                 float mip = max(0.0, ceil(log2(max(rho, 1.0))));
                 // Keep the filter footprint inside this sprite, including
                 // the next mip sampled by trilinear filtering during zoom.
                 vec2 inset = min(vec2(0.5), vec2(0.5 * exp2(mip)) / pixels);
                 vec2 repeated = clamp(vModelUv > 0.5 ? uv : fract(uv), inset, vec2(1.0) - inset);
                 return rectMin + repeated * size;
             }

             void main() {
                #ifndef VSS_IRIS
                gl_FragDepth = gl_FragCoord.w;
                #endif
                 // Derivatives must be evaluated before coverage/cutout discard.
                 vec2 uvDx = dFdx(tileUv);
                 vec2 uvDy = dFdy(tileUv);
                 float footprint = max(length(uvDx), length(uvDy));
                if (vSurfaceVisible < 0.5) {
                    discard;
                }
                // Compare in the main target's depth space. Equal/quantized
                // depths belong to Voxy; a closer prediction still occludes
                // distant cut faces. Sky must remain fillable at any distance.
                float mainDepth = texelFetch(MainDepth, ivec2(gl_FragCoord.xy), 0).r;
                #ifdef VSS_IRIS
                float clipDepth = VssZeroToOne ? mainDepth : mainDepth * 2.0 - 1.0;
                vec4 mainView = VssInverseProjection * vec4(gl_FragCoord.xy / VssViewport * 2.0 - 1.0, clipDepth, 1.0);
                vec4 predictedView = VssInverseProjection * vec4(gl_FragCoord.xy / VssViewport * 2.0 - 1.0,
                        VssZeroToOne ? gl_FragCoord.z : gl_FragCoord.z * 2.0 - 1.0, 1.0);
                float predictedDistance = abs(predictedView.z / predictedView.w);
                float fluidTie = 0.02 * max(1.0, predictedDistance / max(abs(dot(vFaceNormal, relative)), 0.02));
                // Prefer real geometry in the same surface neighbourhood.
                // Far cut faces must still be occluded by closer predicted ground.
                if (mainDepth != VssClearDepth && abs(mainView.w) > 1e-10 && abs(mainView.z / mainView.w)
                        <= predictedDistance + (vWater > 0.5 ? fluidTie : min(16.0, max(1.0, Spacing * 2.0)))) discard;
                #else
                // Rasterizer W retains reciprocal clip distance. Recovering it
                // from window Z subtracts nearly equal numbers at altitude and
                // makes coplanar water alternate ownership in horizontal bands.
                float distance = 1.0 / max(gl_FragCoord.w, 1e-30);
                // A fluid tie is measured perpendicular to the face, not
                // along view Z. At grazing angles a two-centimetre plane
                // tolerance spans much more clip distance. This also bounds
                // subpixel rasterization error on large near-clipped quads.
                float tieBias = vWater > 0.5 ? DepthBias * max(1.0,
                        distance / max(abs(dot(vFaceNormal, relative)), DepthBias)) : DepthBias;
                distance += tieBias;
                float projectedDepth = clamp((-MainDepthPlanes.x
                        + MainDepthPlanes.y / distance) * 0.5 + 0.5, 0.0, 1.0);
                float realDistance = MainDepthPlanes.y / (mainDepth * 2.0 - 1.0 + MainDepthPlanes.x);
                bool originalVoxyDepth = false;
                // Voxy's final blit saturates all geometry beyond vanilla's
                // far plane to the last depth bin. That bin cannot establish
                // which of two distant surfaces is closer.
                if (VoxyDepthAvailable && mainDepth >= 1.0 - 2.0 / 16777215.0 && mainDepth < 1.0) {
                    float raw = texelFetch(VoxyDepth, ivec2(gl_FragCoord.xy), 0).r;
                    if (raw > 0.0 && raw < 1.0) {
                        vec2 uv = gl_FragCoord.xy / vec2(textureSize(VoxyDepth, 0));
                        vec4 clip = vec4(uv * 2.0 - 1.0, raw * 2.0 - 1.0, 1.0);
                        float denominator = dot(VoxyDistanceDenominator, clip);
                        if (abs(denominator) > 1e-10) {
                            realDistance = dot(VoxyDistanceNumerator, clip) / denominator;
                            originalVoxyDepth = realDistance > 0.0;
                            // One far-depth quantization bin spans centimetres
                            // or more in world space. A fixed 0.02-block bias
                            // cannot consistently break coplanar water ties.
                            // Use the near end of a two-bin interval (storage
                            // rounding plus shader arithmetic), not a distance
                            // percentage that would hide a distinct riverbed.
                            if (originalVoxyDepth && vWater > 0.5) {
                                vec4 nearClip = clip;
                                nearClip.z = max(-1.0, clip.z - 4.0 / 16777215.0);
                                float nearDenominator = dot(VoxyDistanceDenominator, nearClip);
                                if (abs(nearDenominator) > 1e-10) {
                                    float nearDistance = dot(VoxyDistanceNumerator, nearClip) / nearDenominator;
                                    if (nearDistance > 0.0) realDistance = min(realDistance, nearDistance);
                                }
                            }
                        }
                    }
                }
                if (mainDepth < 1.0 && (originalVoxyDepth ? distance >= realDistance : projectedDepth >= mainDepth)) {
                    discard;
                }
                // Sample-space error must not put the coarse fallback above
                // a rendered real surface. Bound the preference so distant
                // Voxy cut faces do not punch through foreground prediction.
                if (vWater < 0.5 && mainDepth < 1.0 && abs(realDistance - distance) <= min(16.0, max(1.0, Spacing * 2.0))) discard;
                #endif
                // Boundary faces belong to the solid on their inward side.
                ivec3 section = ivec3(floor((relative - vFaceNormal * 0.01
                        - VanillaMaskOrigin) / 16.0));
                // A compiled column can outlive its renderer's distance limit.
                // In particular, flying above loaded chunks must reveal the
                // prediction fallback. Use camera-relative 3D distance, which
                // stays independent of pitch, FOV and walking view bobbing.
                bool withinVanilla = dot(relative, relative) < VanillaRenderDistance * VanillaRenderDistance;
                if ((vRealBoundary & 1u) != 0u) {
                    // A height connector owns the prediction side even when
                    // its higher surface is in the compiled real column.
                    vec3 outward = (vRealBoundary & 2u) != 0u ? -vFaceNormal : vFaceNormal;
                    ivec3 inside = ivec3(floor((relative - outward * 0.01 - VanillaMaskOrigin) / 16.0));
                    ivec3 outside = ivec3(floor((relative + outward * 0.01 - VanillaMaskOrigin) / 16.0));
                    bool realInside = all(greaterThanEqual(inside, ivec3(0))) && all(lessThan(inside, VanillaMaskSize))
                            && texelFetch(VanillaMask, inside, 0).r > 0.5;
                    bool realOutside = all(greaterThanEqual(outside, ivec3(0))) && all(lessThan(outside, VanillaMaskSize))
                            && texelFetch(VanillaMask, outside, 0).r > 0.5;
                    if (!withinVanilla || !realInside || realOutside) discard;
                } else if (withinVanilla
                        && all(greaterThanEqual(section, ivec3(0)))
                        && all(lessThan(section, VanillaMaskSize))
                        && texelFetch(VanillaMask, section, 0).r > 0.5) {
                    discard;
                }
                float detailWeight = 1.0 - smoothstep(0.5, 1.0, footprint);
                ivec2 sourceCell = ivec2(int(vCell) % CellAxis, int(vCell) / CellAxis);
                ivec2 cell = vCellLocal > 0.5
                        ? ivec2(int(floor(localXZ.x / Spacing)),
                                int(floor(localXZ.y / Spacing)))
                        : sourceCell;
                // A wall lies on a cell boundary. Its perpendicular coordinate
                // must stay on the owning column, including at the tile edge.
                if (vCoverageAxis == 1u) cell.x = sourceCell.x;
                if (vCoverageAxis == 2u) cell.y = sourceCell.y;
                if (cell.x < 0 || cell.y < 0 || cell.x >= CellAxis
                        || cell.y >= CellAxis
                        || texelFetch(Yield, cell, 0).r < 0.5) {
                    discard;
                }
                vec4 color = vColor * ColorModulator;
                if (color.a == 0.0) {
                    discard;
                }
                bool lava = vWater > 1.5 && vWater < 2.5;
                vec3 lightmap = LodLightmap > 0.5
                        ? texture(Lightmap, lava ? vec2(15.5 / 16.0, 15.5 / 16.0)
                                                 : vec2(0.5 / 16.0, (vSkyLight + 0.5) / 16.0)).rgb
                        : vec3(1.0);
                // The vanilla water sprite has alpha 180/255. Preserve the
                // resource pack's actual alpha whenever a material is present.
                vec4 albedo = vec4(color.rgb, vWater > 0.5 && vWater < 1.5 ? 180.0 / 255.0 : 1.0);
                vec3 materialTint = vec3(1.0);
                vec2 materialUv = fract(tileUv);
                if (UseAverage == 0 && vSprite > 0.5 && int(vSprite) < textureSize(SpriteTable, 0).x) {
                    vec4 averageRow = texelFetch(SpriteTable, ivec2(int(vSprite), 1), 0);
                    vec3 tintRatio = min(color.rgb / max(averageRow.rgb, vec3(0.004)),
                            vec3(1.25));
                    materialTint = tintRatio;
                    vec4 rect = texelFetch(SpriteTable, ivec2(int(vSprite), 0), 0);
                    vec2 size = rect.zw - rect.xy;
                    if (detailWeight > 0.0
                            && size.x > 0.0 && size.y > 0.0) {
                        vec2 uv = spriteUv(rect.xy, rect.zw, tileUv, uvDx, uvDy);
                        materialUv = uv;
                        vec4 tex = textureGrad(Atlas, uv, uvDx * size, uvDy * size);
                        if (vCutout > 0.5 && tex.a < 0.1) {
                            discard;
                        }
                        albedo = mix(vec4(color.rgb, averageRow.a),
                                vec4(tex.rgb * tintRatio, tex.a), detailWeight);
                    } else {
                        if (vCutout > 0.5 && size.x > 0.0 && size.y > 0.0) {
                            vec2 uv = spriteUv(rect.xy, rect.zw, tileUv, uvDx, uvDy);
                            if (textureGrad(Atlas, uv, uvDx * size, uvDy * size).a < 0.1) {
                                discard;
                            }
                        }
                        albedo = vec4(color.rgb, averageRow.a);
                    }
                }
                #ifdef VSS_IRIS
                uint face = abs(vFaceNormal.y) > 0.5 ? 1u : abs(vFaceNormal.x) > 0.5
                        ? (vFaceNormal.x > 0.0 ? 5u : 4u) : (vFaceNormal.z > 0.0 ? 3u : 2u);
                uint customId = VssMaterialIds[clamp(int(vSprite), 0, 255)];
                vec2 lm = lava ? vec2(248.0/256.0) : vec2(8.0/256.0, (vSkyLight * 16.0 + 8.0)/256.0);
                albedo.a = max(albedo.a, OpaqueAlpha);
                albedo.rgb /= max(materialTint, vec3(0.004));
                voxy_emitFragment(VoxyFragmentParameters(albedo, floor(tileUv), materialUv,
                        face, 0u, lm, vec4(materialTint, 1.0), customId));
                #else
                vec3 shaded = albedo.rgb * vColor.a * lightmap;
                // Prediction and Voxy use different lighting/tint paths. A
                // short near-field grade keeps the handoff from reading as
                // a bright green band while leaving the far horizon at full
                // brightness. LodHazeStart is the exact-field boundary in
                // blocks, supplied by the renderer for this purpose.
                float seamStart = max(64.0, LodHazeStart * 0.50);
                float seamEnd = max(seamStart + 1.0, LodHazeStart * 1.75);
                float seam = 1.0 - smoothstep(seamStart, seamEnd, vDistance);
                shaded *= mix(1.0, LodColorScale, seam);
                float alpha = max(albedo.a, OpaqueAlpha);
                fragColor = vec4(vssPredictionFog(shaded, relative.xz), alpha);
                #endif
            }
            """;
}
