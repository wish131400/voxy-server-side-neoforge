package dev.xantha.vss.compat;

/** Reserve whole sibling groups before descending; a full queue keeps the parent's mesh visible. */
public final class VoxyTraversalShader {
    private VoxyTraversalShader() { }
    private static final String MARKER = "// VSS bounded traversal queues";

    public static String patch(String path, String source) {
        if (!path.equals("voxy:lod/hierarchical/traversal_dev.comp") || source.contains(MARKER)) return source;
        if (!source.contains("void pushNodesInit(uint nodeCount)") || !source.contains("pushNodesInit(children);"))
            throw new IllegalStateException("Unsupported native Voxy traversal queue contract");
        source = replaceFunction(source, "void pushNodesInit(uint nodeCount)", """
                // VSS bounded traversal queues
                bool vssReserveNodes(uint nodeCount) {
                    nodePushIndex = uint(-1);
                    if (queueIdx + 1u >= uint(nodeQueueMetadata.length())) return false;
                    uint capacity = uint(nodeQueueSink.length());
                    // Keep Voxy's single atomic reservation; never spin or roll the counter back.
                    // Node IDs are 24 bit, so even a full native tree cannot wrap this uint counter.
                    uint index = atomicAdd(nodeQueueMetadata[queueIdx+1u].w, nodeCount);
                    if (index > capacity || nodeCount > capacity - index) {
                        // The one group straddling the boundary leaves at most seven unused slots.
                        // Mark them empty: the next iteration bounds source reads by buffer length.
                        for (uint tail = index; tail < capacity; tail++) nodeQueueSink[tail] = SENTINAL_OUT_OF_BOUNDS;
                        return false;
                    }
                    uint inc = ((index+LOCAL_SIZE)>>LOCAL_SIZE_BITS)-(index>>LOCAL_SIZE_BITS);
                    atomicAdd(nodeQueueMetadata[queueIdx+1u].x, inc);
                    nodePushIndex = index;
                    return true;
                }
                """);
        source = source.replace("void enqueueChildren(in UnpackedNode node)",
                "void enqueueSelfForRender(in UnpackedNode node);\nvoid enqueueChildren(in UnpackedNode node)");
        source = source.replace("pushNodesInit(children);", """
                if (!vssReserveNodes(children)) {
                        if (hasMesh(node)) enqueueSelfForRender(node);
                        return;
                    }
                """);
        return source.replace("if (nodeQueueMetadata[queueIdx].w <= gl_GlobalInvocationID.x)",
                "if (nodeQueueMetadata[queueIdx].w <= gl_GlobalInvocationID.x || gl_GlobalInvocationID.x >= uint(nodeQueueSource.length()))");
    }

    static String replaceFunction(String source, String signature, String replacement) {
        int start = source.indexOf(signature), open = source.indexOf('{', start), end = open + 1, depth = 1;
        if (start < 0 || open < 0) throw new IllegalStateException("Missing Voxy shader function: " + signature);
        while (end < source.length() && depth != 0) {
            char c = source.charAt(end++);
            if (c == '{') depth++; else if (c == '}') depth--;
        }
        if (depth != 0) throw new IllegalStateException("Unbalanced Voxy shader function: " + signature);
        return source.substring(0, start) + replacement + source.substring(end);
    }
}
