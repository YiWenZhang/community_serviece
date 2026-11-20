package cn.community.ai;

import cn.community.common.*;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本地Lucene HNSW向量索引 + 中文BM25，以RRF融合；MySQL保存可重建的权威副本。 */
@Component
public class VectorStore {
  private final Directory directory;
  private final IndexWriter writer;
  private final CJKAnalyzer analyzer = new CJKAnalyzer();
  private final Json json;

  public VectorStore(@Value("${app.storage}") String root, Json json) throws IOException {
    this.json = json;
    directory = FSDirectory.open(Path.of(root, "policy-vectors"));
    writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
    writer.commit();
  }

  public synchronized void rebuild(List<Map<String, Object>> rows) throws IOException {
    writer.deleteAll();
    for (var row : rows) add(row);
    writer.commit();
  }

  public synchronized void replace(long document, List<Map<String, Object>> rows)
      throws IOException {
    writer.deleteDocuments(new Term("document", Long.toString(document)));
    for (var r : rows) add(r);
    writer.commit();
  }

  public synchronized void delete(long document) throws IOException {
    writer.deleteDocuments(new Term("document", Long.toString(document)));
    writer.commit();
  }

  private void add(Map<String, Object> row) throws IOException {
    var d = new Document();
    d.add(new StringField("id", row.get("id").toString(), Field.Store.YES));
    d.add(new StringField("document", row.get("document_id").toString(), Field.Store.NO));
    d.add(new StringField("community", row.get("community_id").toString(), Field.Store.NO));
    String space = row.get("embedding_space").toString();
    d.add(new StringField("space", space, Field.Store.NO));
    d.add(
        new TextField(
            "text",
            row.get("content") + " " + row.get("summary") + " " + row.get("keywords"),
            Field.Store.NO));
    var values = (List<?>) json.map("{\"v\":" + row.get("embedding_json") + "}").get("v");
    float[] v = new float[values.size()];
    for (int i = 0; i < v.length; i++) v[i] = ((Number) values.get(i)).floatValue();
    d.add(new KnnFloatVectorField("v_" + space, v, VectorSimilarityFunction.COSINE));
    writer.addDocument(d);
  }

  public List<Long> search(
      long community, String text, float[] vector, String space, List<Long> eligible, int limit)
      throws IOException {
    if (eligible.isEmpty()) return List.of();
    var filter =
        new BooleanQuery.Builder()
            .add(
                new TermQuery(new Term("community", Long.toString(community))),
                BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term("space", space)), BooleanClause.Occur.FILTER)
            .add(
                new TermInSetQuery(
                    "document", eligible.stream().map(id -> new BytesRef(id.toString())).toList()),
                BooleanClause.Occur.FILTER)
            .build();
    try (var reader = DirectoryReader.open(writer)) {
      var searcher = new IndexSearcher(reader);
      var terms = new LinkedHashSet<String>();
      try (var stream = analyzer.tokenStream("text", text)) {
        var token = stream.addAttribute(CharTermAttribute.class);
        stream.reset();
        while (stream.incrementToken() && terms.size() < 128) terms.add(token.toString());
        stream.end();
      }
      var words = new BooleanQuery.Builder();
      for (String term : terms)
        words.add(new TermQuery(new Term("text", term)), BooleanClause.Occur.SHOULD);
      var lexical =
          new BooleanQuery.Builder()
              .add(filter, BooleanClause.Occur.FILTER)
              .add(words.build(), BooleanClause.Occur.MUST)
              .build();
      int candidates = Math.max(24, limit * 4);
      var a =
          searcher.search(
              new KnnFloatVectorQuery("v_" + space, vector, candidates, filter), candidates);
      var b = searcher.search(lexical, candidates);
      Map<Long, Double> scores = new HashMap<>();
      for (var hits : List.of(a, b))
        for (int i = 0; i < hits.scoreDocs.length; i++) {
          long id =
              Long.parseLong(searcher.storedFields().document(hits.scoreDocs[i].doc).get("id"));
          scores.merge(id, 1.0 / (60 + i + 1), Double::sum);
        }
      return scores.entrySet().stream()
          .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
          .limit(limit)
          .map(Map.Entry::getKey)
          .toList();
    }
  }

  @PreDestroy
  public void close() throws IOException {
    writer.close();
    directory.close();
    analyzer.close();
  }
}
