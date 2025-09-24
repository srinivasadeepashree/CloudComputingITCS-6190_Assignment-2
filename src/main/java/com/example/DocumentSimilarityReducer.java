package com.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.URI;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;

public class DocumentSimilarityReducer {

    /**
     * ===== Job A Reducer =====
     * Routes by key prefix:
     *  - "DOC:<docId>"  -> count unique WORDs -> write to named output "docSizes"
     *  - "INV:<word>"   -> unique docIds      -> write to named output "inverted"
     */
    public static class ReducerA extends Reducer<Text, Text, Text, Text> {
        private MultipleOutputs<Text, Text> mos;
        private final Text outKey = new Text();
        private final Text outVal = new Text();

        @Override
        protected void setup(Context ctx) {
            mos = new MultipleOutputs<Text, Text>(ctx);
        }

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context ctx) throws IOException, InterruptedException {
            String k = key.toString();

            if (k.startsWith("DOC:")) {
                // count UNIQUE words for this doc
                HashSet<String> words = new HashSet<String>();
                for (Text v : values) {
                    String s = v.toString();
                    if (s.startsWith("WORD:")) {
                        words.add(s.substring(5));
                    }
                }
                String docId = k.substring(4);
                outKey.set(docId);
                outVal.set(Integer.toString(words.size()));
                mos.write("docSizes", outKey, outVal);

            } else if (k.startsWith("INV:")) {
                // inverted index: UNIQUE docIds per word
                String word = k.substring(4);
                HashSet<String> docs = new HashSet<String>();
                for (Text v : values) docs.add(v.toString());
                for (String d : docs) {
                    outKey.set(word);
                    outVal.set(d);
                    mos.write("inverted", outKey, outVal);
                }
            } else {
                // ignore
            }
        }

        @Override
        protected void cleanup(Context ctx) throws IOException, InterruptedException {
            mos.close();
        }
    }

    /**
     * ===== Job B Reducer (aggregating) =====
     * Input: key=word, values=[docId...]
     * Loads docSizes via Distributed Cache.
     * Aggregates intersections per (docA, docB) across ALL words and prints ONCE per pair.
     */
    public static class ReducerB extends Reducer<Text, Text, Text, Text> {
        private final HashMap<String, Integer> docSizes = new HashMap<String, Integer>();
        private final HashMap<String, Integer> pairIntersections = new HashMap<String, Integer>();
        private final DecimalFormat df = new DecimalFormat("#.00");
        private final Text outKey = new Text();
        private static final Text EMPTY = new Text("");

        @Override
        protected void setup(Context ctx) throws IOException {
            URI[] cacheFiles = ctx.getCacheFiles();
            if (cacheFiles == null) return;

            for (URI uri : cacheFiles) {
                Path p = new Path(uri.getPath());
                FileSystem fs = FileSystem.get(ctx.getConfiguration());
                if (!fs.exists(p)) continue;

                FSDataInputStream in = null;
                BufferedReader br = null;
                try {
                    in = fs.open(p);
                    br = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.trim().split("\\t");
                        if (parts.length < 2) continue;
                        try {
                            docSizes.put(parts[0], Integer.valueOf(Integer.parseInt(parts[1])));
                        } catch (NumberFormatException ignore) {}
                    }
                } finally {
                    if (br != null) br.close();
                    if (in != null) in.close();
                }
            }
        }

        private static String ordered(String a, String b) {
            return (a.compareTo(b) <= 0) ? (a + "|" + b) : (b + "|" + a);
        }

        @Override
        protected void reduce(Text word, Iterable<Text> docIds, Context ctx) throws IOException, InterruptedException {
            ArrayList<String> docs = new ArrayList<String>();
            HashSet<String> seen = new HashSet<String>();
            for (Text d : docIds) {
                String s = d.toString();
                if (seen.add(s)) docs.add(s);
            }

            for (int i = 0; i < docs.size(); i++) {
                for (int j = i + 1; j < docs.size(); j++) {
                    String pair = ordered(docs.get(i), docs.get(j));
                    Integer current = pairIntersections.get(pair);
                    pairIntersections.put(pair, (current == null ? 1 : current + 1));
                }
            }
        }

        @Override
        protected void cleanup(Context ctx) throws IOException, InterruptedException {
            for (Map.Entry<String, Integer> entry : pairIntersections.entrySet()) {
                String pair = entry.getKey();
                int inter = entry.getValue().intValue();

                String[] ab = pair.split("\\|", 2);
                if (ab.length < 2) continue;
                String a = ab[0];
                String b = ab[1];

                Integer asz = docSizes.get(a);
                Integer bsz = docSizes.get(b);
                if (asz == null || bsz == null) continue;

                int union = asz.intValue() + bsz.intValue() - inter;
                if (union <= 0) continue;

                double jacc = (double) inter / (double) union;

                outKey.set(a + ", " + b + " Similarity: " + df.format(jacc));
                ctx.write(outKey, EMPTY);
            }
        }
    }
}
