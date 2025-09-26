# Assignment 2: Document Similarity using MapReduce

**Name:** Srinivasa Deepashree

**Student ID:** 801426883

## Approach and Implementation

### Mapper Design
The Mapper class processes each input line representing a single document, where the key is the byte offset of the line and the value is the document's text. It emits two types of output key-value pairs:

- Key: `"DOC:<docId>"`, Value: `"WORD:<word>"`
    - This helps count the unique words in each document to determine document sizes.

- Key: `"INV:<word>"`, Value: `<docId>`
    - This builds an inverted index mapping words to the documents that contain them.

The Mapper normalizes document text to lowercase and strips non-alphanumeric characters, ensuring consistent tokenization. Emitting these pairs prepares data for calculating document sizes and producing an inverted index, foundational for computing Jaccard similarity.

### Reducer Design
The Reducer processes keys from the Mapper:

- For keys starting `"DOC:<docId>"`, it gathers all unique words emitted, counts them, and writes the document size (number of unique words) to a named output called `"docSizes"`.

- For keys starting `"INV:<word>"`, it aggregates all unique document IDs containing that word and writes inverted index entries to a named output `"inverted"`.

In the second job's Reducer, it reads the inverted index data where the input key is a word and the values are document IDs. It loads document sizes from a distributed cache. It calculates intersection counts for all document pairs sharing that word, storing these counts in memory. After all keys are processed, it computes the union sizes for pairs and then calculates Jaccard similarity as:
<img width="524" height="74" alt="Screenshot 2025-09-24 at 12 16 02 AM" src="https://github.com/user-attachments/assets/a889f92c-59a9-4d89-a29e-39af0b54957e" />
 
The Reducer emits each document pair with its similarity score rounded to two decimal places.

### Overall Data Flow
1. Input: The initial input files contain lines, each line with a document ID followed by text.

2. Mapper Phase (Job A):
   Each document line is tokenized into unique words.
   The Mapper emits document-word pairs for counting document sizes and word-document pairs for an inverted index.

3. Reducer Phase (Job A):
   Aggregates document-word pairs to calculate unique word counts per document (document sizes).
   Aggregates word-document pairs to build the inverted index mapping words to documents.

4. Shuffle/Sort:
   The framework groups keys and their associated values for reducers.

5. Mapper Phase (Job B):
   Reads the inverted index (word -> document list) output from Job A.

6. Reducer Phase (Job B):
   Loads document sizes from distributed cache.
   Aggregates intersection counts for each unique pair of documents sharing a word.
   Calculates union size and computes Jaccard similarity for each pair.
   Emits the final similarity scores for all unique document pairs.

7. Output:
   Final output files list document pairs and their similarity scores formatted as:
   
   `<DocumentID1>, <DocumentID2> Similarity: <score>`
---

## Repository Structure
```bash
├── README.md
├── .gitignore
├── LICENSE
├── docker-compose.yml
├── hadoop.env
├── pom.xml
├── shared-folder
│   ├── input
│   │   ├── data
│   │   │   ├── input.txt
│   ├── output
│   │   ├── final
│   │   ├── jobA
├── src
│   ├── main
│   │   ├── java
│   │   │   ├── com
│   │   │   │   ├── example
│   │   │   │   │   ├──controller
│   │   │   │   │   │   ├── DocumentSimilarityDriver.java
│   │   │   │   │   ├── DocumentSimilarityMapper.java
│   │   │   │   │   ├── DocumentSimilarityReducer.java
```
---

## Setup and Execution

### 1. **Start the Hadoop Cluster**

Run the following command to start the Hadoop cluster:

```bash
docker compose up -d
```

### 2. **Build the Code**

Build the code using Maven:

```bash
mvn clean package
```

### 4. **Copy JAR to Docker Container**

Copy the JAR file to the Hadoop ResourceManager container:

```bash
docker cp target/DocumentSimilarity-0.0.1-SNAPSHOT.jar resourcemanager:/opt/hadoop-3.2.1/share/hadoop/mapreduce/
```

### 5. **Move Dataset to Docker Container**

Copy the dataset to the Hadoop ResourceManager container:

```bash
docker cp shared-folder/input/data/input.txt resourcemanager:/opt/hadoop-3.2.1/share/hadoop/mapreduce/
```

### 6. **Connect to Docker Container**

Access the Hadoop ResourceManager container:

```bash
docker exec -it resourcemanager /bin/bash
```

Navigate to the Hadoop directory:

```bash
cd /opt/hadoop-3.2.1/share/hadoop/mapreduce/
```

### 7. **Set Up HDFS**

Create a folder in HDFS for the input dataset:

```bash
hadoop fs -mkdir -p /input/data
```

Copy the input dataset to the HDFS folder:

```bash
hadoop fs -put ./input.txt /input/data
```

### 8. **Execute the MapReduce Job**

Run your MapReduce job using the following command: Here I got an error saying output already exists so I changed it to output1 instead as destination folder

```bash
hadoop jar /opt/hadoop-3.2.1/share/hadoop/mapreduce/DocumentSimilarity-0.0.1-SNAPSHOT.jar com.example.controller.DocumentSimilarityDriver /input/data/input.txt /output1
```

### 9. **View the Output**

To view the output of your MapReduce job, use:

```bash
hadoop fs -cat /output1/*
```

### 10. **Copy Output from HDFS to Local OS**

To copy the output from HDFS to your local machine:

1. Use the following command to copy from HDFS:
```bash
hdfs dfs -get /output1 /opt/hadoop-3.2.1/share/hadoop/mapreduce/
```

2. use Docker to copy from the container to your local machine:
```bash
exit 
```
```bash
docker cp resourcemanager:/opt/hadoop-3.2.1/share/hadoop/mapreduce/output1/ shared-folder/output/
```
---

## Challenges and Solutions

### Challenge:
`java.lang.ClassNotFoundException` for DocumentSimilarityDriver when running the Hadoop jar command.
This error occurs because the Hadoop runtime cannot find the driver class inside the jar file.

### Root Cause and Solution:
- The project’s Java source files were not placed under the expected directory structure inside the Maven src folder.
- In Maven projects, Java source code must be inside the src/main/java folder following the package structure.
- For example, if the class DocumentSimilarityDriver is in package com.example.controller, the source file should be located at:
```bash
src/main/java/com/example/controller/DocumentSimilarityDriver.java
```
- If the .java files are directly under src or some other directory not recognized by Maven, the build process will not include these classes in the jar.
- Consequently, the jar will be missing the class and the runtime throws ClassNotFoundException.

### How the issue was fixed by adding the java folder under src:
- Creating the folder path `src/main/java` and placing the Java source files inside according to their package hierarchy allowed Maven to correctly locate and compile them.
- Running `mvn clean package` after this correctly built a jar that contains the `DocumentSimilarityDriver` and other classes in their proper paths.
- The built jar then included the right `.class` files, making the class available at runtime.
- This eliminated the `ClassNotFoundException` error when the jar was executed using the Hadoop command.
---
## Sample Input

**Input from `small_dataset.txt`**
```
Document1 This is a sample document containing words
Document2 Another document that also has words
Document3 Sample text with different words
```
## Sample Output

**Output from `small_dataset.txt`**
```
"Document1, Document2 Similarity: 0.56"
"Document1, Document3 Similarity: 0.42"
"Document2, Document3 Similarity: 0.50"
```
## Obtained Output:
Check the `output/dataNode1/final` and `output/dataNode3/final` folder for the similarity scores
```
Document2, Document3 Similarity: .14	
Document1, Document3 Similarity: .09	
Document1, Document2 Similarity: .14	
```

### Cluster Performance Comparison: 3 DataNodes vs 1 DataNode

| Feature             | 3 DataNodes                          | 1 DataNode                      |
|---------------------|--------------------------------------|---------------------------------|
| **Speed**           | Fast, parallel execution             | Slow, serial execution          |
| **Parallelism**     | High – tasks split across nodes      | None – all tasks on one node    |
| **Fault Tolerance** | High – survives node failure         | None – single point of failure  |
| **Resources**       | Large CPU, RAM, and disk pool        | Limited to one node’s capacity  |
| **Throughput**      | High                                 | Low                             |
| **Data Availability** | Redundant storage, robust          | No redundancy, risky            |
| **Cluster Health**  | Stable and resilient                 | Vulnerable to disruptions       |
