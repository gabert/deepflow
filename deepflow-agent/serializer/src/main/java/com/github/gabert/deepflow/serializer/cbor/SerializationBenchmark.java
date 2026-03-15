package com.github.gabert.deepflow.serializer.cbor;

import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

/**
 * Standalone CBOR serialization benchmark for ObjectIdRegistry / EnvelopeSerializer.
 *
 * Run with: javac + java, or from your IDE — no test framework needed.
 *
 * Object graph: - Tree: depth 4, branching 3 → ~120 Node objects - Nodes carry a String label, int[20] payload,
 * List<String> tags - Shared leaves referenced from multiple parents (DAG / diamond) - Cycle: deepest-left leaf.parent
 * = root - TransientNode chain (non-Serializable, length 20, cyclic) - Mixed bag: heterogeneous List<Object> combining
 * all of the above
 */
public class SerializationBenchmark {

   // -----------------------------------------------------------------------
   // Object model
   // -----------------------------------------------------------------------

   static class Node implements Serializable {

      @Serial
      private static final long serialVersionUID = 1L;

      final String label;
      final int[] payload;
      final List<String> tags;

      Node parent;
      List<Node> children = new ArrayList<>();
      Node sharedLeaf;

      Node(String label) {
         this.label = label;
         this.payload = new int[20];
         this.tags = new ArrayList<>();
         Arrays.fill(this.payload, label.hashCode());
         for (int i = 0; i < 4; i++)
            tags.add(label + "-tag-" + i);
      }
   }

   static class TransientNode {

      final String id;
      final Object[] slots;
      TransientNode next;

      TransientNode(String id) {
         this.id = id;
         this.slots = new Object[6];
      }
   }

   // -----------------------------------------------------------------------
   // Graph builders
   // -----------------------------------------------------------------------

   static Node buildTree(int depth, int branching) {
      Node[] sharedLeaves = new Node[branching];
      for (int i = 0; i < branching; i++)
         sharedLeaves[i] = new Node("shared-leaf-" + i);

      Node root = buildSubtree("n", depth, branching, sharedLeaves, new AtomicInteger());

      // Cycle: deepest-left leaf → root
      Node cursor = root;
      while (!cursor.children.isEmpty())
         cursor = cursor.children.get(0);
      cursor.parent = root;

      return root;
   }

   private static Node buildSubtree(String label,
                                    int depth,
                                    int branching,
                                    Node[] sharedLeaves,
                                    AtomicInteger counter) {
      Node node = new Node(label + "#" + counter.getAndIncrement());
      node.sharedLeaf = sharedLeaves[counter.get() % sharedLeaves.length];
      if (depth == 0)
         return node;
      for (int i = 0; i < branching; i++) {
         Node child = buildSubtree(label + "-" + i, depth - 1, branching, sharedLeaves, counter);
         child.parent = node;
         node.children.add(child);
      }
      return node;
   }

   static TransientNode buildChain(int length, Node treeRoot) {
      TransientNode head = new TransientNode("chain-0");
      TransientNode cur = head;
      for (int i = 1; i < length; i++) {
         TransientNode next = new TransientNode("chain-" + i);
         for (int s = 0; s < next.slots.length - 1; s++)
            next.slots[s] = "slot-" + i + "-" + s;
         next.slots[next.slots.length - 1] = treeRoot;
         cur.next = next;
         cur = next;
      }
      cur.next = head; // close cycle
      return head;
   }

   static List<Object> buildMixedBag(Node treeRoot, TransientNode chain) {
      List<Object> bag = new ArrayList<>();
      bag.add(treeRoot);
      bag.add(chain);
      bag.add("plain string");
      bag.add(new int[] { 1, 2, 3, 4, 5 });
      bag.add(treeRoot.children.get(0)); // shared ref into tree
      bag.add(treeRoot.sharedLeaf); // diamond ref
      bag.add(Map.of("key1", "val1", "key2", 42));
      bag.add(List.of("a", "b", "c"));
      return bag;
   }

   // -----------------------------------------------------------------------
   // Serialization
   // -----------------------------------------------------------------------

   private static byte[] serializeToCbor(ObjectMapper mapper, Object value) throws Exception {
      ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
      mapper.writeValue(buf, value);
      return buf.toByteArray();
   }

   // -----------------------------------------------------------------------
   // Benchmark runner
   // -----------------------------------------------------------------------

   static final int WARMUP_ROUNDS = 500;
   static final int MEASURE_ROUNDS = 5_000;

   private static void run(String label, ObjectMapper mapper, Object root) throws Exception {
      System.out.printf("%n═══ %s ═══%n", label);

      for (int i = 0; i < WARMUP_ROUNDS; i++)
         serializeToCbor(mapper, root);

      byte[] sample = serializeToCbor(mapper, root);

      Runtime rt = Runtime.getRuntime();
      System.gc();
      long heapBefore = rt.totalMemory() - rt.freeMemory();

      long start = System.nanoTime();
      for (int i = 0; i < MEASURE_ROUNDS; i++)
         serializeToCbor(mapper, root);
      long elapsed = System.nanoTime() - start;

      long heapAfter = rt.totalMemory() - rt.freeMemory();

      System.out.printf("  Rounds       : %,d%n", MEASURE_ROUNDS);
      System.out.printf("  Total time   : %.1f ms%n", elapsed / 1_000_000.0);
      System.out.printf("  Avg latency  : %.2f µs / serialization%n", elapsed / 1_000.0 / MEASURE_ROUNDS);
      System.out.printf("  Throughput   : %,.0f serializations / sec%n", MEASURE_ROUNDS / (elapsed / 1_000_000_000.0));
      System.out.printf("  Output size  : %,d bytes%n", sample.length);
      System.out.printf("  Heap delta   : %+,d bytes (indicative)%n", heapAfter - heapBefore);
   }

   // -----------------------------------------------------------------------
   // Main
   // -----------------------------------------------------------------------

   public static void main(String[] args) throws Exception {
      Node tree = buildTree(4, 3);
      TransientNode chain = buildChain(20, tree);
      List<Object> mixBag = buildMixedBag(tree, chain);

      ObjectMapper mapper = new ObjectMapper(new CBORFactory());
      mapper.setVisibility(
         mapper.getSerializationConfig()
               .getDefaultVisibilityChecker()
               .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
               .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
               .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE));
      mapper.registerModule(new EnvelopeModule());

      System.out.println("ObjectIdRegistry / EnvelopeSerializer — CBOR serialization benchmark");
      System.out.printf("Warm-up: %,d rounds  |  Measurement: %,d rounds%n", WARMUP_ROUNDS, MEASURE_ROUNDS);

      run("Full tree (depth=4, branch=3, ~120 nodes, cycle)", mapper, tree);
      run("TransientNode chain (length=20, cyclic)", mapper, chain);
      run("Mixed bag (tree + chain + strings + arrays + maps)", mapper, mixBag);
      run("Shared leaf (hot-path: already-registered object)", mapper, tree.sharedLeaf);

      System.out.println("\nDone.");
   }
}
