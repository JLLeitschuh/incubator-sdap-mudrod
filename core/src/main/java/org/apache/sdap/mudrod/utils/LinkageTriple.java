/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sdap.mudrod.utils;

import org.apache.sdap.mudrod.driver.ESDriver;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

/**
 * ClassName: LinkageTriple Function: Vocabulary linkage operations
 */
public class LinkageTriple implements Serializable {

  /**
   *
   */
  private static final long serialVersionUID = 1L;
  // keyAId: ID of term A
  public long keyAId;
  // keyBId: ID of term B
  public long keyBId;
  // weight: The relationship between term A and Term B
  public double weight;
  // keyA: TermA
  public String keyA;
  // keyB: TermB
  public String keyB;
  // df: Format number
  private static DecimalFormat df;

  private static final Logger LOG = LoggerFactory.getLogger(LinkageTriple.class);

  public LinkageTriple() {
    NumberFormat nf = NumberFormat.getNumberInstance(Locale.ENGLISH);
    df = (DecimalFormat) nf;
    df.applyPattern("#.00");
  }

  /**
   * TODO Output linkage triples in string format.
   *
   * @see java.lang.Object#toString()
   */
  @Override
  public String toString() {
    return keyA + "," + keyB + ":" + weight;
  }

  public static void insertTriples(ESDriver es, List<LinkageTriple> triples, String index, String type) throws IOException {
    LinkageTriple.insertTriples(es, triples, index, type, false, false);
  }

  public static void insertTriples(ESDriver es, List<LinkageTriple> triples, String index, String type, Boolean bTriple, boolean bSymmetry) throws IOException {
    es.deleteType(index, type);
    if (bTriple) {
      LinkageTriple.addMapping(es, index, type);
    }

    if (triples == null) {
      return;
    }

    es.createBulkProcessor();
    for (LinkageTriple triple : triples) {

      XContentBuilder jsonBuilder = jsonBuilder().startObject();
      if (bTriple) {

        jsonBuilder.field("concept_A", triple.keyA);
        jsonBuilder.field("concept_B", triple.keyB);

      } else {
        jsonBuilder.field("keywords", triple.keyA + "," + triple.keyB);
      }
      double tripleWeight = 0;
      try {
        tripleWeight = Double.parseDouble(df.format(triple.weight));
      } catch (NumberFormatException  e) {
        // do nothing, triple weight is 0 as it cannot be parsed
      }
      jsonBuilder.field("weight", tripleWeight);
      jsonBuilder.endObject();

      IndexRequest ir = new IndexRequest(index, type).source(jsonBuilder);
      es.getBulkProcessor().add(ir);

      if (bTriple && bSymmetry) {
        XContentBuilder symmetryJsonBuilder = jsonBuilder().startObject();
        symmetryJsonBuilder.field("concept_A", triple.keyB);
        symmetryJsonBuilder.field("concept_B", triple.keyA);

        symmetryJsonBuilder.field("weight", Double.parseDouble(df.format(triple.weight)));

        symmetryJsonBuilder.endObject();

        IndexRequest symmetryir = new IndexRequest(index, type).source(symmetryJsonBuilder);
        es.getBulkProcessor().add(symmetryir);
      }
    }
    es.destroyBulkProcessor();
  }

  public static void addMapping(ESDriver es, String index, String type) {
    XContentBuilder mapping;
    try {
      mapping = jsonBuilder().startObject().startObject(type).startObject("properties").startObject("concept_A").field("type", "string").field("index", "not_analyzed").endObject()
          .startObject("concept_B").field("type", "string").field("index", "not_analyzed").endObject()

          .endObject().endObject().endObject();

      es.getClient().admin().indices().preparePutMapping(index).setType(type).setSource(mapping).execute().actionGet();
    } catch (IOException e) {
      LOG.error("Failed to add mapping : ", e);
    }
  }

  public static void standardTriples(ESDriver es, String index, String type) throws IOException {
    es.createBulkProcessor();

    SearchResponse sr = es.getClient().prepareSearch(index).setTypes(type).setQuery(QueryBuilders.matchAllQuery()).setSize(0)
        .addAggregation(AggregationBuilders.terms("concepts").field("concept_A").size(0)).execute().actionGet();
    Terms concepts = sr.getAggregations().get("concepts");

    for (Terms.Bucket entry : concepts.getBuckets()) {
      String concept = (String) entry.getKey();
      double maxSim = LinkageTriple.getMaxSimilarity(es, index, type, concept);
      if (maxSim == 1.0) {
        continue;
      }

      SearchResponse scrollResp = es.getClient().prepareSearch(index).setTypes(type).setScroll(new TimeValue(60000)).setQuery(QueryBuilders.termQuery("concept_A", concept))
          .addSort("weight", SortOrder.DESC).setSize(100).execute().actionGet();

      while (true) {
        for (SearchHit hit : scrollResp.getHits().getHits()) {
          Map<String, Object> metadata = hit.getSource();
          double sim = (double) metadata.get("weight");
          double newSim = sim / maxSim;
          UpdateRequest ur = es.generateUpdateRequest(index, type, hit.getId(), "weight", Double.parseDouble(df.format(newSim)));
          es.getBulkProcessor().add(ur);
        }

        scrollResp = es.getClient().prepareSearchScroll(scrollResp.getScrollId()).setScroll(new TimeValue(600000)).execute().actionGet();
        if (scrollResp.getHits().getHits().length == 0) {
          break;
        }
      }
    }

    es.destroyBulkProcessor();
  }

  private static double getMaxSimilarity(ESDriver es, String index, String type, String concept) {

    double maxSim = 1.0;
    SearchRequestBuilder builder = es.getClient().prepareSearch(index).setTypes(type).setQuery(QueryBuilders.termQuery("concept_A", concept)).addSort("weight", SortOrder.DESC).setSize(1);

    SearchResponse usrhis = builder.execute().actionGet();
    SearchHit[] hits = usrhis.getHits().getHits();
    if (hits.length == 1) {
      SearchHit hit = hits[0];
      Map<String, Object> result = hit.getSource();
      maxSim = (double) result.get("weight");
    }

    if (maxSim == 0.0) {
      maxSim = 1.0;
    }

    return maxSim;
  }
}
