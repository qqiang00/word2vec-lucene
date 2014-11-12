/*
 *  Copyright (c) 2014 RONDHUIT Co.,Ltd.
 *  
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package com.rondhuit.w2v.lucene;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rondhuit.commons.IOUtils;

public class Word2vec {

  static final int VOCAB_MAX_SIZE      = 30000000;
  static final int EXP_TABLE_SIZE      = 1000;
  static final int MAX_EXP             = 6;
  static final int TABLE_SIZE          = 100000000;
  static final int MAX_SENTENCE_LENGTH = 1000;
  static final Charset ENCODING = Charset.forName("UTF-8");

  long timeStart;
  int trainWords = 0;
  int vocabSize;
  int vocabMaxSize = 1000;
  int minReduce = 1;
  VocabWord[] vocab;
  Map<String, Integer> vocabIndexMap = new HashMap<String, Integer>();
  static double[] syn0, syn1, syn1neg;
  int[] table;
  IndexReader reader;
  TopDocs topDocs;
  final Analyzer analyzer;
  
  private final Config config;
  
  static final double[] expTable = new double[EXP_TABLE_SIZE + 1];
  
  static{
    for (int i = 0; i < EXP_TABLE_SIZE; i++) {
      expTable[i] = Math.exp((i / (double)EXP_TABLE_SIZE * 2 - 1) * MAX_EXP); // Precompute the exp() table
      expTable[i] = expTable[i] / (expTable[i] + 1);                          // Precompute f(x) = x / (x + 1)
    }
  }
  
  private static Logger logger = LoggerFactory.getLogger(Word2vec.class);
  
  public Word2vec(Config config){
    this.config = config;
    vocab = new VocabWord[vocabMaxSize];
    analyzer = loadAnalyzer(config.getAnalyzer());
  }
  
  public Config getConfig(){
    return config;
  }
  
  static Analyzer loadAnalyzer(String fqcn){
    try {
      return (Analyzer)Class.forName(fqcn).newInstance();
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  static class TrainModelThread extends Thread {
    final Word2vec vec;
    final Config config;
    float alpha;
    final float startingAlpha;
    final int id, trainWords, vocabSize;
    final long timeStart;
    final int[] table;
    final VocabWord[] vocab;
    final String field;
    final Analyzer analyzer;
    final IndexReader reader;
    final TopDocs topDocs;
    int tdPos;
    static int wordCountActual = 0;
    
    public TrainModelThread(Word2vec vec, Config config, int id){
      this.vec = vec;
      this.config = config;
      this.alpha = config.getAlpha();
      this.startingAlpha = alpha;
      this.id = id;
      this.table = vec.table;
      this.trainWords = vec.trainWords;
      this.timeStart = vec.timeStart;
      this.vocabSize = vec.vocabSize;
      this.vocab = vec.vocab;
      this.field = config.getField();
      this.analyzer = vec.analyzer;
      this.reader = vec.reader;
      this.topDocs = vec.topDocs;
    }
    
    public void run(){
      final int iter = config.getIter();
      final int layer1Size = config.getLayer1Size();
      final int numThreads = config.getNumThreads();
      final int window = config.getWindow();
      final int negative = config.getNegative();
      final boolean cbow = config.useContinuousBagOfWords();
      final boolean hs = config.useHierarchicalSoftmax();
      final float sample = config.getSample();
      
      try{
        int word = 0, sentence_length = 0, sentence_position = 0, a, b, c, d, last_word, l1, l2, target;
        int[] sen = new int[MAX_SENTENCE_LENGTH + 1];
        long cw;
        long word_count = 0, last_word_count = 0;
        long label, local_iter = iter;
        long next_random = id;
        double f, g;
        long timeNow;
        double[] neu1 = new double[layer1Size];
        double[] neu1e = new double[layer1Size];

        tdPos = topDocs.totalHits / numThreads * id;
        while(true){
          if (word_count - last_word_count > 10000) {
            wordCountActual += word_count - last_word_count;
            last_word_count = word_count;
            timeNow = System.currentTimeMillis();
            System.err.printf("%cAlpha: %f  Progress: %.2f%%  Words/thread/sec: %.2fk  ", 13, alpha,
                wordCountActual / (float)(iter * trainWords + 1) * 100,
                wordCountActual / (float)(timeNow - timeStart + 1));
            System.err.flush();
            alpha = startingAlpha * (1 - wordCountActual / (float)(iter * trainWords + 1));
            if (alpha < startingAlpha * 0.0001) alpha = startingAlpha * 0.0001F;
          }
          if (sentence_length == 0) {
            while(true){
              word = readWordIndex();
              if(word == -2) break;                // EOF
              if(word == -1) continue;
              word_count++;
              if (word == 0) break;
              // The subsampling randomly discards frequent words while keeping the ranking same
              if (sample > 0) {
                double ran = (Math.sqrt(vocab[word].cn / (sample * trainWords)) + 1) * (sample * trainWords) / vocab[word].cn;
                next_random = nextRandom(next_random);
                if (ran < (next_random & 0xFFFF) / (double)65536) continue;
              }
              sen[sentence_length] = word;
              sentence_length++;
              if (sentence_length >= MAX_SENTENCE_LENGTH) break;
            }
            sentence_position = 0;
          }
          if(word == -2 /* eof? */ || (word_count > trainWords / numThreads)){
            wordCountActual += word_count - last_word_count;
            local_iter--;
            if (local_iter == 0) break;
            word_count = 0;
            last_word_count = 0;
            sentence_length = 0;
            tdPos = topDocs.totalHits / numThreads * id;
            continue;
          }
          word = sen[sentence_position];
          if (word == -1) continue;
          for (c = 0; c < layer1Size; c++) neu1[c] = 0;
          for (c = 0; c < layer1Size; c++) neu1e[c] = 0;
          next_random = nextRandom(next_random);
          b = (int)next_random % window;
          if (cbow) {  //train the cbow architecture
            // in -> hidden
            cw = 0;
            for (a = b; a < window * 2 + 1 - b; a++) if (a != window) {
              c = sentence_position - window + a;
              if (c < 0) continue;
              if (c >= sentence_length) continue;
              last_word = sen[c];
              if (last_word == -1) continue;
              for (c = 0; c < layer1Size; c++) neu1[c] += syn0[c + last_word * layer1Size];
              cw++;
            }
            if(cw != 0) {
              for (c = 0; c < layer1Size; c++) neu1[c] /= cw;
              if (hs) for (d = 0; d < vocab[word].codelen; d++) {
                f = 0;
                l2 = vocab[word].point[d] * layer1Size;
                // Propagate hidden -> output
                for (c = 0; c < layer1Size; c++) f += neu1[c] * syn1[c + l2];
                if (f <= -MAX_EXP) continue;
                else if (f >= MAX_EXP) continue;
                else f = expTable[(int)((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2))];
                // 'g' is the gradient multiplied by the learning rate
                g = (1 - vocab[word].code[d] - f) * alpha;
                // Propagate errors output -> hidden
                for (c = 0; c < layer1Size; c++) neu1e[c] += g * syn1[c + l2];
                // Learn weights hidden -> output
                for (c = 0; c < layer1Size; c++) syn1[c + l2] += g * neu1[c];
              }
              // NEGATIVE SAMPLING
              if (negative > 0) for (d = 0; d < negative + 1; d++) {
                if (d == 0) {
                  target = word;
                  label = 1;
                } else {
                  next_random = nextRandom(next_random);
                  target = table[Math.abs((int)((next_random >> 16) % TABLE_SIZE))];
                  if (target == 0) target = Math.abs((int)(next_random % (vocabSize - 1) + 1));
                  if (target == word) continue;
                  label = 0;
                }
                l2 = target * layer1Size;
                f = 0;
                for (c = 0; c < layer1Size; c++) f += neu1[c] * syn1neg[c + l2];
                if (f > MAX_EXP) g = (label - 1) * alpha;
                else if (f < -MAX_EXP) g = (label - 0) * alpha;
                else g = (label - expTable[(int)((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2))]) * alpha;
                for (c = 0; c < layer1Size; c++) neu1e[c] += g * syn1neg[c + l2];
                for (c = 0; c < layer1Size; c++) syn1neg[c + l2] += g * neu1[c];
              }
              // hidden -> in
              for (a = b; a < window * 2 + 1 - b; a++) if (a != window) {
                c = sentence_position - window + a;
                if (c < 0) continue;
                if (c >= sentence_length) continue;
                last_word = sen[c];
                if (last_word == -1) continue;
                for (c = 0; c < layer1Size; c++) syn0[c + last_word * layer1Size] += neu1e[c];
              }
            }
          } else {  //train skip-gram
            for (a = b; a < window * 2 + 1 - b; a++) if (a != window) {
              c = sentence_position - window + a;
              if (c < 0) continue;
              if (c >= sentence_length) continue;
              last_word = sen[c];
              if (last_word == -1) continue;
              l1 = last_word * layer1Size;
              for (c = 0; c < layer1Size; c++) neu1e[c] = 0;
              // HIERARCHICAL SOFTMAX
              if (hs) for (d = 0; d < vocab[word].codelen; d++) {
                f = 0;
                l2 = vocab[word].point[d] * layer1Size;
                // Propagate hidden -> output
                for (c = 0; c < layer1Size; c++) f += syn0[c + l1] * syn1[c + l2];
                if (f <= -MAX_EXP) continue;
                else if (f >= MAX_EXP) continue;
                else f = expTable[(int)((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2))];
                // 'g' is the gradient multiplied by the learning rate
                g = (1 - vocab[word].code[d] - f) * alpha;
                // Propagate errors output -> hidden
                for (c = 0; c < layer1Size; c++) neu1e[c] += g * syn1[c + l2];
                // Learn weights hidden -> output
                for (c = 0; c < layer1Size; c++) syn1[c + l2] += g * syn0[c + l1];
              }
              // NEGATIVE SAMPLING
              if (negative > 0) for (d = 0; d < negative + 1; d++) {
                if (d == 0) {
                  target = word;
                  label = 1;
                } else {
                  next_random = nextRandom(next_random);
                  target = table[Math.abs((int)((next_random >> 16) % TABLE_SIZE))];
                  if (target == 0) target = Math.abs((int)(next_random % (vocabSize - 1) + 1));
                  if (target == word) continue;
                  label = 0;
                }
                l2 = target * layer1Size;
                f = 0;
                for (c = 0; c < layer1Size; c++) f += syn0[c + l1] * syn1neg[c + l2];
                if (f > MAX_EXP) g = (label - 1) * alpha;
                else if (f < -MAX_EXP) g = (label - 0) * alpha;
                else g = (label - expTable[(int)((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2))]) * alpha;
                for (c = 0; c < layer1Size; c++) neu1e[c] += g * syn1neg[c + l2];
                for (c = 0; c < layer1Size; c++) syn1neg[c + l2] += g * syn0[c + l1];
              }
              // Learn weights input -> hidden
              for (c = 0; c < layer1Size; c++) syn0[c + l1] += neu1e[c];
            }
          }
          sentence_position++;
          if (sentence_position >= sentence_length) {
            sentence_length = 0;
            continue;
          }
        }
      }
      catch(IOException e){
        throw new RuntimeException(e);
      }
      // exit from thread
      synchronized (vec) {
        vec.threadCount--;
        vec.notify();
      }
    }

    /**
     * Reads a word and returns its index in the vocabulary
     * @param br
     * @return
     * @throws IOException
     */
    int readWordIndex() throws IOException {
      String word = readWord();
      return word == null ? -2 : vec.searchVocab(word);
    }
    
    TokenStream tokenStream = null;
    CharTermAttribute termAtt = null;
    String[] values = new String[]{};
    int valPos = 0;

    /**
     * Reads a single word from index
     * @return null if end of index or end of document
     * @throws IOException
     */
    String readWord() throws IOException {
      
      while(true){
        // check the tokenStream first
        if(tokenStream != null && tokenStream.incrementToken()){
          return new String(termAtt.buffer(), 0, termAtt.length());
        }

        if(tokenStream != null)
          tokenStream.close();
        if(valPos < values.length){
          tokenStream = analyzer.tokenStream(field, values[valPos++]);
          termAtt = tokenStream.getAttribute(CharTermAttribute.class);
          tokenStream.reset();
        }
        else{
          if(tdPos >= topDocs.totalHits){
            tokenStream = null;
            return null;   // end of index
          }
          Document doc = reader.document(topDocs.scoreDocs[tdPos++].doc);
          values = doc.getValues(field);   // This method returns an empty array when there are no matching fields.
                                           // It never returns null.
          valPos = 0;
          tokenStream = null;
        }
      }
    }
  }
  
  int threadCount;
  
  public void trainModel() throws IOException {
    System.err.printf("Starting training using Lucene index %s\n", config.getIndexDir());

    final int layer1Size = config.getLayer1Size();
    Directory dir = FSDirectory.open(new File(config.getIndexDir()));
    reader = DirectoryReader.open(dir);

    learnVocabFromIndex();
    
    sortVocab();
    logger.info("Vocab size: {}\n", vocabSize);
    logger.info("Words in train file: {}\n", trainWords);

    if(config.getOutputFile() == null) return;

    initNet();
    if(config.getNegative() > 0)
      initUnigramTable();

    timeStart = System.currentTimeMillis();

    threadCount = config.getNumThreads();
    for(int i = 0; i < config.getNumThreads(); i++){
      new TrainModelThread(this, config, i).start();
    }
    synchronized (this) {
      while(threadCount > 0){
        try {
          wait();
        }
        catch (InterruptedException ignored) {}
      }
    }

    OutputStream os = null;
    Writer w = null;
    PrintWriter pw = null;

    try{
      os = new FileOutputStream(config.getOutputFile());
      w = new OutputStreamWriter(os, ENCODING);
      pw = new PrintWriter(w);
      
      if(config.getClasses() == 0){
        // Save the word vectors
        logger.info("now saving the word vectors to the file {}", config.getOutputFile());
        pw.printf("%d %d\n", vocabSize, layer1Size);
        for(int i = 0; i < vocabSize; i++){
          pw.print(vocab[i].word);
          for(int j = 0; j < layer1Size; j++){
            pw.printf(" %f", syn0[i * layer1Size + j]);
          }
          pw.println();
        }
      }
      else{
        // Run K-means on the word vectors
        logger.info("now computing K-means clustering (K={})", config.getClasses());
        final int MAX_ITER = 10;
        final int clcn = config.getClasses();
        final int[] centcn = new int[clcn];
        final int[] cl = new int[vocabSize];
        final int centSize = clcn * layer1Size;
        final double[] cent = new double[centSize];
        
        for(int i = 0; i < vocabSize; i++)
          cl[i] = i % clcn;
        
        for(int it = 0; it < MAX_ITER; it++) {
          for(int j = 0; j < centSize; j++)
            cent[j] = 0;
          for(int j = 0; j < clcn; j++)
            centcn[j] = 1;
          for(int k = 0; k < vocabSize; k++){
            for(int l = 0; l < layer1Size; l++){
              cent[layer1Size * cl[k] + l] += syn0[k * layer1Size + l];
            }
            centcn[cl[k]]++;
          }
          for(int j = 0; j < clcn; j++){
            double closev = 0;
            for(int k = 0; k < layer1Size; k++){
              cent[layer1Size * j + k] /= centcn[j];
              closev += cent[layer1Size * j + k] * cent[layer1Size * j + k];
            }
            closev = Math.sqrt(closev);
            for(int k = 0; k < layer1Size; k++){
              cent[layer1Size * j + k] /= closev;
            }
          }
          for(int k = 0; k < vocabSize; k++){
            double closev = -10;
            int closeid = 0;
            for(int l = 0; l < clcn; l++) {
              double x = 0;
              for(int j = 0; j < layer1Size; j++){
                x += cent[layer1Size * l + j] * syn0[k * layer1Size + j];
              }
              if (x > closev) {
                closev = x;
                closeid = l;
              }
            }
            cl[k] = closeid;
          }
        }
        // Save the K-means classes
        logger.info("now saving the result of K-means clustering to the file {}", config.getOutputFile());
        for(int i = 0; i < vocabSize; i++){
          pw.printf("%s %d\n", vocab[i].word, cl[i]);
        }
      }
    }
    finally{
      reader.close();
      IOUtils.closeQuietly(pw);
      IOUtils.closeQuietly(w);
      IOUtils.closeQuietly(os);
    }
  }
  
  void learnVocabFromIndex() throws IOException {
    vocabIndexMap.clear();
    vocabSize = 0;

    final String field = config.getField();
    final Terms terms = MultiFields.getTerms(reader, field);
    final BytesRef maxTerm = terms.getMax();
    final BytesRef minTerm = terms.getMin();
    Query q = new TermRangeQuery(field, minTerm, maxTerm, true, true);
    IndexSearcher searcher = new IndexSearcher(reader);
    topDocs = searcher.search(q, Integer.MAX_VALUE);

    TermsEnum termsEnum = null;
    termsEnum = terms.iterator(termsEnum);

    termsEnum.seekCeil(new BytesRef());
    BytesRef term = termsEnum.term();
    while(term != null){
      int p = addWordToVocab(term.utf8ToString());
      vocab[p].cn = (int)termsEnum.totalTermFreq();
      term = termsEnum.next();
    }
  }

  /**
   * Returns position of a word in the vocabulary; if the word is not found, returns -1
   * @param word
   * @return
   */
  int searchVocab(String word){
    Integer pos = vocabIndexMap.get(word);
    return pos == null ? -1 : pos.intValue();
  }

  /**
   * Adds a word to the vocabulary
   * @param word
   * @return
   */
  int addWordToVocab(String word){
    vocab[vocabSize] = new VocabWord(word);
    vocabSize++;

    // Reallocate memory if needed
    if(vocabSize + 2 >= vocabMaxSize){
      vocabMaxSize += 1000;
      VocabWord[] temp = new VocabWord[vocabMaxSize];
      System.arraycopy(vocab, 0, temp, 0, vocabSize);
      vocab = temp;
    }
    vocabIndexMap.put(word, vocabSize - 1);
    return vocabSize - 1;
  }

  /**
   * Reduces the vocabulary by removing infrequent tokens
   */
  void reduceVocab(){
    int j = 0;
    for(int i = 0; i < vocabSize; i++){
      if(vocab[i].cn > minReduce){
        vocab[j].cn = vocab[i].cn;
        vocab[j].word = vocab[i].word;
        j++;
      }
    }
    vocabSize = j;
    vocabIndexMap.clear();
    for(int i = 0; i < vocabSize; i++){
      vocabIndexMap.put(vocab[i].word, i);
    }
    minReduce++;
  }

  /**
   * Used later for sorting by word counts
   *
   */
  static class VocabWordComparator implements Comparator<VocabWord> {
    @Override
    public int compare(VocabWord o1, VocabWord o2) {
      return o2.cn - o1.cn;
    }
  }

  /**
   * Sorts the vocabulary by frequency using word counts
   */
  void sortVocab(){
    // Sort the vocabulary and keep </s> at the first position
    List<VocabWord> list = new ArrayList<VocabWord>(vocabSize - 1);
    for(int i = 1; i < vocabSize; i++){
      list.add(vocab[i]);
    }
    Collections.sort(list, new VocabWordComparator());
    
    // re-build vocabIndexMap
    vocabIndexMap.clear();
    final int size = vocabSize - 1;
    trainWords = 0;
    // Hash will be re-computed, as after the sorting it is not actual
    setVocabIndexMap(vocab[0], 0);
    for(int i = 0; i < size; i++){
      // Words occuring less than min_count times will be discarded from the vocab
      if(list.get(i).cn < config.getMinCount()){
        vocabSize--;
      }
      else{
        // Hash will be re-computed, as after the sorting it is not actual
        setVocabIndexMap(list.get(i), i + 1);
      }
    }

    String tempWord = vocab[0].word;
    int tempCn = vocab[0].cn;
    vocab = new VocabWord[vocabSize];
    vocab[0] = new VocabWord(tempWord);
    vocab[0].cn = tempCn;
    for(int i = 0; i < vocabSize - 1; i++){
      vocab[i + 1] = new VocabWord(list.get(i).word);
      vocab[i + 1].cn = list.get(i).cn;
    }
  }
  
  void setVocabIndexMap(VocabWord src, int pos){
    vocabIndexMap.put(src.word, pos);
    trainWords += src.cn;
  }
  
  void initUnigramTable(){
    long trainWordsPow = 0;
    double d1, power = 0.75;
    table = new int[TABLE_SIZE];
    for(int i = 0; i < vocabSize; i++){
      trainWordsPow += Math.pow(vocab[i].cn, power);
    }
    int i = 0;
    d1 = Math.pow(vocab[i].cn, power) / (double)trainWordsPow;
    for(int j = 0; j < TABLE_SIZE; j++){
      table[j] = i;
      if((double)j / (double)TABLE_SIZE > d1) {
        i++;
        d1 += Math.pow(vocab[i].cn, power) / (double)trainWordsPow;
      }
      if (i >= vocabSize)
        i = vocabSize - 1;
    }
  }
  
  void initNet(){
    final int layer1Size = config.getLayer1Size();
    
    syn0 = posixMemAlign128(vocabSize * layer1Size);

    if(config.useHierarchicalSoftmax()){
      syn1 = posixMemAlign128(vocabSize * layer1Size);
      for(int i = 0; i < vocabSize; i++){
        for(int j = 0; j < layer1Size; j++){
          syn1[i * layer1Size + j] = 0;
        }
      }
    }

    if(config.getNegative() > 0){
      syn1neg = posixMemAlign128(vocabSize * layer1Size);
      for(int i = 0; i < vocabSize; i++){
        for(int j = 0; j < layer1Size; j++){
          syn1neg[i * layer1Size + j] = 0;
        }
      }
    }

    long nextRandom = 1;
    for(int i = 0; i < vocabSize; i++){
      for(int j = 0; j < layer1Size; j++){
        nextRandom = nextRandom(nextRandom);
        syn0[i * layer1Size + j] = (((nextRandom & 0xFFFF) / (double)65536) - 0.5) / layer1Size;
      }
    }
    createBinaryTree();
  }
  
  static double[] posixMemAlign128(int size){
    final int surplus = size % 128;
    if(surplus > 0){
      int div = size / 128;
      return new double[(div + 1) * 128];
    }
    return new double[size];
  }
  
  static long nextRandom(long nextRandom){
    return nextRandom * 25214903917L + 11;
  }

  /**
   * Create binary Huffman tree using the word counts. 
   * Frequent words will have short uniqe binary codes
   */
  void createBinaryTree() {
    int[] point = new int[VocabWord.MAX_CODE_LENGTH];
    char[] code = new char[VocabWord.MAX_CODE_LENGTH];
    int[] count = new int[vocabSize * 2 + 1];
    char[] binary = new char[vocabSize * 2 + 1];
    int[] parentNode = new int[vocabSize * 2 + 1];
    
    for(int i = 0; i < vocabSize; i++)
      count[i] = vocab[i].cn;
    for(int i = vocabSize; i < vocabSize * 2; i++)
      count[i] = Integer.MAX_VALUE;
    int pos1 = vocabSize - 1;
    int pos2 = vocabSize;
    // Following algorithm constructs the Huffman tree by adding one node at a time
    int min1i, min2i;
    for(int i = 0; i < vocabSize - 1; i++) {
      // First, find two smallest nodes 'min1, min2'
      if (pos1 >= 0) {
        if (count[pos1] < count[pos2]) {
          min1i = pos1;
          pos1--;
        } else {
          min1i = pos2;
          pos2++;
        }
      } else {
        min1i = pos2;
        pos2++;
      }
      if (pos1 >= 0) {
        if (count[pos1] < count[pos2]) {
          min2i = pos1;
          pos1--;
        } else {
          min2i = pos2;
          pos2++;
        }
      } else {
        min2i = pos2;
        pos2++;
      }
      count[vocabSize + i] = count[min1i] + count[min2i];
      parentNode[min1i] = vocabSize + i;
      parentNode[min2i] = vocabSize + i;
      binary[min2i] = 1;
    }
    // Now assign binary code to each vocabulary word
    for(int j = 0; j < vocabSize; j++){
      int k = j;
      int i = 0;
      while (true) {
        code[i] = binary[k];
        point[i] = k;
        i++;
        k = parentNode[k];
        if(k == vocabSize * 2 - 2) break;
      }
      vocab[j].codelen = i;
      vocab[j].point[0] = vocabSize - 2;
      for(k = 0; k < i; k++) {
        vocab[j].code[i - k - 1] = code[k];
        vocab[j].point[i - k] = point[k] - vocabSize;
      }
    }
  }
}
