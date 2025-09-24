package com.example.controller;

import java.net.URI;

import com.example.DocumentSimilarityMapper;
import com.example.DocumentSimilarityReducer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.MultipleOutputs;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

public class DocumentSimilarityDriver extends Configured implements Tool {

    @Override
    public int run(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: hadoop jar <jar> com.example.controller.DocumentSimilarityDriver <input> <outBase>");
            return 1;
        }

        Configuration conf = getConf();
        FileSystem fs = FileSystem.get(conf);

        Path input   = new Path(args[0]);
        Path outBase = new Path(args[1]);
        Path jobAOut = new Path(outBase, "jobA");
        Path jobBOut = new Path(outBase, "final");

        // Clean outputs for re-runs
        if (fs.exists(jobAOut)) fs.delete(jobAOut, true);
        if (fs.exists(jobBOut)) fs.delete(jobBOut, true);

        /* ============ Job A ============ */
        Job jobA = Job.getInstance(conf, "DocSim-JobA (docSizes + inverted)");
        jobA.setJarByClass(DocumentSimilarityDriver.class);

        jobA.setMapperClass(DocumentSimilarityMapper.MapperA.class);
        jobA.setReducerClass(DocumentSimilarityReducer.ReducerA.class);

        jobA.setMapOutputKeyClass(Text.class);
        jobA.setMapOutputValueClass(Text.class);
        jobA.setOutputKeyClass(Text.class);
        jobA.setOutputValueClass(Text.class);

        jobA.setInputFormatClass(TextInputFormat.class);
        jobA.setOutputFormatClass(TextOutputFormat.class);

        FileInputFormat.addInputPath(jobA, input);
        FileOutputFormat.setOutputPath(jobA, jobAOut);

        // Named outputs from ReducerA
        MultipleOutputs.addNamedOutput(jobA, "docSizes", TextOutputFormat.class, Text.class, Text.class);
        MultipleOutputs.addNamedOutput(jobA, "inverted", TextOutputFormat.class, Text.class, Text.class);

        if (!jobA.waitForCompletion(true)) return 1;

        /* ============ Job B ============ */
        Job jobB = Job.getInstance(conf, "DocSim-JobB (pairs + Jaccard)");
        jobB.setJarByClass(DocumentSimilarityDriver.class);

        jobB.setMapperClass(DocumentSimilarityMapper.MapperB.class);
        jobB.setReducerClass(DocumentSimilarityReducer.ReducerB.class);

        jobB.setMapOutputKeyClass(Text.class);
        jobB.setMapOutputValueClass(Text.class);
        jobB.setOutputKeyClass(Text.class);
        jobB.setOutputValueClass(Text.class);

        jobB.setInputFormatClass(TextInputFormat.class);
        jobB.setOutputFormatClass(TextOutputFormat.class);

        // Inputs = all "inverted-*" files produced by Job A
        FileStatus[] invertedFiles = fs.globStatus(new Path(jobAOut, "inverted-*"));
        if (invertedFiles == null || invertedFiles.length == 0) {
            System.err.println("No inverted-* files under " + jobAOut);
            return 1;
        }
        for (FileStatus f : invertedFiles) {
            FileInputFormat.addInputPath(jobB, f.getPath());
        }

        // Cache = all "docSizes-*" (used by ReducerB)
        FileStatus[] sizeFiles = fs.globStatus(new Path(jobAOut, "docSizes-*"));
        if (sizeFiles == null || sizeFiles.length == 0) {
            System.err.println("No docSizes-* files under " + jobAOut);
            return 1;
        }
        for (FileStatus f : sizeFiles) {
            jobB.addCacheFile(new URI(f.getPath().toString()));
        }

        FileOutputFormat.setOutputPath(jobB, jobBOut);

        // One reducer so all pairs are globally aggregated
        jobB.setNumReduceTasks(1);

        return jobB.waitForCompletion(true) ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        int ec = ToolRunner.run(new Configuration(), new DocumentSimilarityDriver(), args);
        System.exit(ec);
    }
}
