package dev.xantha.vss.compat;

import java.util.regex.Pattern;

/** Keeps model indentation in block units without changing Voxy's voxel topology. */
public final class VoxySurfaceHeights {
    private VoxySurfaceHeights() { }
    private static final Pattern BASE_POINT = Pattern.compile(
            "quad\\.basePoint\\s*=\\s*\\(quadStart\\s*\\*\\s*lodScale\\)\\s*\\+\\s*vec3\\(baseSection\\s*<<\\s*5\\)\\s*;");

    public static String patchShader(String path, String source) {
        return patchFluid(patchInset(source));
    }

    private static String patchInset(String source) {
        // ShaderLoader.parse receives the root shader after imports have been expanded.
        if (!source.contains("void setupQuad(") || source.contains("// VSS world-space model inset")) return source;
        // Maintained forks own their fluid datum or already normalize model indentation.
        if (source.contains("resolveFluidTopIndentation(")
                || source.matches("(?s).*extractFaceIndentation\\(faceData\\)\\s*/\\s*lodScale.*")) return source;
        var match = BASE_POINT.matcher(source);
        if (!match.find()) return source;
        int end = match.end();
        if (match.find()) return source;
        int setup = source.indexOf("void setupQuad(");
        if (setup >= end) return source;
        String tail = source.substring(end);
        String anchor = "quad.uvCorner = faceSize.xz;";
        if (!tail.contains(anchor)) return source;
        String correction = """

                // VSS world-space model inset: leave full-cube occupancy boundaries untouched.
                // Moving sampled tops after face culling would expose faces the mesher omitted.
                if (face == 1u && lodLevel != 0u) {
                    quad.basePoint.y += (lodScale - 1.0) * extractFaceIndentation(faceData);
                }
            """;
        String sideCorrection = """

                if (face >= 2u && face <= 5u && lodLevel != 0u) {
                    float vssTopInset = extractFaceIndentation(model.faceData[1]);
                    if (vssTopInset > 0.0) {
                        float vssVerticalSize = float(face < 4u ? quadSize.y : quadSize.x);
                        float vssTop = (extractPos(rawQuad).y + vssVerticalSize) * lodScale
                                + float(baseSection.y << 5) - vssTopInset;
                        float vssSize = max(0.0, (vssTop - quad.basePoint.y) / lodScale);
            %s
                        if (face < 4u) quad.quadSizeAddin.y = vssSize;
                        else quad.quadSizeAddin.x = vssSize;
                    }
                }
            """.formatted(source.contains("#ifdef USE_SINGLE_TRI")
                    ? "#ifdef USE_SINGLE_TRI\n                        vssSize *= 2.0;\n#endif" : "");
        return source.substring(0, end) + correction + tail.replace(anchor, anchor + sideCorrection);
    }

    private static String patchFluid(String source) {
        if(!source.contains("void setupQuad(") || source.contains("// VSS liquid surface remainder"))return source;
        // These forks use the same payload for fluid shapes or biome blend palettes.
        if(source.contains("resolveFluidTopIndentation("))return source;
        String anchor="quad.uvCorner = faceSize.xz;";
        if(!source.contains(anchor))return source;
        // Legacy ivec2 decoding reads 20 bits although native mesh model IDs occupy 16.
        source=source.replaceAll("uint\\s+modelId\\s*=\\s*extractStateId\\(rawQuad\\)\\s*;",
                "uint modelId = extractStateId(rawQuad) & 65535u;");
        String correction="""

                // VSS liquid surface remainder: metadata entered before native greedy merging.
            #ifdef QUAD_DATA_USE_64_BIT
                uint vssLiquidDrop = uint(rawQuad >> 42) & 15u;
            #else
                uint vssLiquidDrop = (uint(rawQuad.y) >> 10u) & 15u;
            #endif
                if (vssLiquidDrop != 0u && lodLevel != 0u) {
                    float vssInset = extractFaceIndentation(model.faceData[1]);
                    float vssExtent = face < 4u ? float(quadSize.y) : float(quadSize.x);
                    float vssLiquidTop = (extractPos(rawQuad).y + (face == 1u ? 1.0 : vssExtent)) * lodScale
                            + float(baseSection.y << 5) - float(vssLiquidDrop) - vssInset;
                    if (face == 1u) quad.basePoint.y = vssLiquidTop;
                    else if (face >= 2u && face <= 5u) {
                        float vssHeight = max(0.0, (vssLiquidTop - quad.basePoint.y) / lodScale);
            %s
                        if (face < 4u) quad.quadSizeAddin.y = vssHeight;
                        else quad.quadSizeAddin.x = vssHeight;
                    }
                }
            """.formatted(source.contains("#ifdef USE_SINGLE_TRI")
                ? "#ifdef USE_SINGLE_TRI\n                        vssHeight *= 2.0;\n#endif" : "");
        if(source.contains("#ifdef GL_ARB_gpu_shader_int64") && !source.contains("#ifdef QUAD_DATA_USE_64_BIT"))
            correction=correction.replace("#ifdef QUAD_DATA_USE_64_BIT","#ifdef GL_ARB_gpu_shader_int64");
        int setup=source.indexOf("void setupQuad("),open=source.indexOf('{',setup),end=open+1,balance=1;
        while(balance!=0 && end<source.length()) {char c=source.charAt(end++);if(c=='{')balance++;if(c=='}')balance--;}
        return balance==0 ? source.substring(0,end-1)+correction+source.substring(end-1) : source;
    }

}
