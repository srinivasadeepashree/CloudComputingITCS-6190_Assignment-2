package com.example;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

public class DocumentSimilarityMapper {

    /** Normalize to lowercase, replace non-alnum with space, return UNIQUE tokens */
    private static Set<String> uniqueTokens(String text) {
        if (text == null) return new HashSet<String>();
        String norm = text.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
        if (norm.isEmpty()) return new HashSet<String>();
        return new HashSet<String>(Arrays.asList(norm.split("\\s+")));
    }

    /**
     * ===== Job A Mapper =====
     * Input:  "<docId> <free text...>"
     * Emits:
     *   - key="DOC:<docId>", value="WORD:<w>"   (to count |doc| unique words)
     *   - key="INV:<w>",     value="<docId>"    (to build inverted index)
     */
    public static class MapperA extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private final Text outVal = new Text();

        @Override
        protected void map(LongWritable key, Text value, Context ctx) throws IOException, InterruptedException {
            String line = value.toString().trim();
            if (line.isEmpty()) return;

            String[] parts = line.split("\\s+", 2);
            if (parts.length < 1) return;

            String docId = parts[0];
            String body  = (parts.length > 1) ? parts[1] : "";

            Set<String> uniq = uniqueTokens(body);
            if (uniq.isEmpty()) return;

            for (String w : uniq) {
                // doc size accounting
                outKey.set("DOC:" + docId);
                outVal.set("WORD:" + w);
                ctx.write(outKey, outVal);

                // inverted index
                outKey.set("INV:" + w);
                outVal.set(docId);
                ctx.write(outKey, outVal);
            }
        }
    }

    /**
     * ===== Job B Mapper =====
     * Input from Job A "inverted-*": "word<TAB>docId"
     * Output: key=word, value=docId
     */
    public static class MapperB extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private final Text outVal = new Text();

        @Override
        protected void map(LongWritable key, Text value, Context ctx) throws IOException, InterruptedException {
            String line = value.toString().trim();
            if (line.isEmpty()) return;
            String[] parts = line.split("\\t");
            if (parts.length < 2) return;
            outKey.set(parts[0]);   // word
            outVal.set(parts[1]);   // docId
            ctx.write(outKey, outVal);
        }
    }
}
