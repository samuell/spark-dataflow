/*
 * Copyright (c) 2014, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.dataflow.spark;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.StringUtf8Coder;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.ApproximateUnique;
import com.google.cloud.dataflow.sdk.transforms.Count;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.Max;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.View;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionList;
import com.google.cloud.dataflow.sdk.values.PCollectionTuple;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import com.google.cloud.dataflow.sdk.values.TupleTagList;
import org.junit.Assert;
import org.junit.Test;

public class MultiOutputWordCountTest {

  private static final TupleTag<String> upper = new TupleTag<>();
  private static final TupleTag<String> lower = new TupleTag<>();
  private static final TupleTag<KV<String, Long>> lowerCnts = new TupleTag<>();
  private static final TupleTag<KV<String, Long>> upperCnts = new TupleTag<>();

  @Test
  public void testRun() throws Exception {
    Pipeline p = Pipeline.create(PipelineOptionsFactory.create());
    PCollection<String> regex = p.apply(Create.of("[^a-zA-Z']+"));
    PCollection<String> w1 = p.apply(Create.of("Here are some words to count", "and some others"));
    PCollection<String> w2 = p.apply(Create.of("Here are some more words", "and even more words"));
    PCollectionList<String> list = PCollectionList.of(w1).and(w2);

    PCollection<String> union = list.apply(Flatten.<String>pCollections());
    PCollectionView<String> regexView = regex.apply(View.<String>asSingleton());
    PCollectionTuple luc = union.apply(new CountWords(regexView));
    PCollection<Long> unique = luc.get(lowerCnts).apply(
        ApproximateUnique.<KV<String, Long>>globally(16));

    EvaluationResult res = SparkPipelineRunner.create().run(p);
    Iterable<KV<String, Long>> actualLower = res.get(luc.get(lowerCnts));
    Iterable<KV<String, Long>> actualUpper = res.get(luc.get(upperCnts));
    Assert.assertEquals("Here", actualUpper.iterator().next().getKey());
    Iterable<Long> actualUniqCount = res.get(unique);
    Assert.assertEquals(9, (long) actualUniqCount.iterator().next());
    int actualTotalWords = res.getAggregatorValue("totalWords", Integer.class);
    Assert.assertEquals(18, actualTotalWords);
    int actualMaxWordLength = res.getAggregatorValue("maxWordLength", Integer.class);
    Assert.assertEquals(6, actualMaxWordLength);
    res.close();
  }

  /**
   * A DoFn that tokenizes lines of text into individual words.
   */
  static class ExtractWordsFn extends DoFn<String, String> {

    private Aggregator<Integer, Integer> totalWords = createAggregator("totalWords",
        new Sum.SumIntegerFn());
    private Aggregator<Integer, Integer> maxWordLength = createAggregator("maxWordLength",
        new Max.MaxIntegerFn());
    private final PCollectionView<String> regex;

    ExtractWordsFn(PCollectionView<String> regex) {
      this.regex = regex;
    }

    @Override
    public void processElement(ProcessContext c) {
      String[] words = c.element().split(c.sideInput(regex));
      for (String word : words) {
        totalWords.addValue(1);
        if (!word.isEmpty()) {
          maxWordLength.addValue(word.length());
          if (Character.isLowerCase(word.charAt(0))) {
            c.output(word);
          } else {
            c.sideOutput(upper, word);
          }
        }
      }
    }
  }

  public static class CountWords extends PTransform<PCollection<String>, PCollectionTuple> {

    private final PCollectionView<String> regex;

    public CountWords(PCollectionView<String> regex) {
      this.regex = regex;
    }

    @Override
    public PCollectionTuple apply(PCollection<String> lines) {
      // Convert lines of text into individual words.
      PCollectionTuple lowerUpper = lines
          .apply(ParDo.of(new ExtractWordsFn(regex))
              .withSideInputs(regex)
              .withOutputTags(lower, TupleTagList.of(upper)));
      lowerUpper.get(lower).setCoder(StringUtf8Coder.of());
      lowerUpper.get(upper).setCoder(StringUtf8Coder.of());
      PCollection<KV<String, Long>> lowerCounts = lowerUpper.get(lower).apply(Count
          .<String>perElement());
      PCollection<KV<String, Long>> upperCounts = lowerUpper.get(upper).apply(Count
          .<String>perElement());
      return PCollectionTuple
          .of(lowerCnts, lowerCounts)
          .and(upperCnts, upperCounts);
    }
  }
}
