/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics.tophits;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.stream.Writeable.Reader;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.aggregations.InternalAggregationTestCase;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Comparator.comparing;

public class InternalTopHitsTests extends InternalAggregationTestCase<InternalTopHits> {
    /**
     * Should the test instances look like they are sorted by some fields (true) or sorted by score (false). Set here because these need
     * to be the same across the entirety of {@link #testReduceRandom()}.
     */
    private final boolean testInstancesLookSortedByField = randomBoolean();
    /**
     * Fields shared by all instances created by {@link #createTestInstance(String, List, Map)}.
     */
    private final SortField[] testInstancesSortFields = testInstancesLookSortedByField ? randomSortFields() : new SortField[0];

    @Override
    protected InternalTopHits createTestInstance(String name, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) {
        int from = 0;
        int requestedSize = between(1, 40);
        int actualSize = between(0, requestedSize);

        float maxScore = Float.MIN_VALUE;
        ScoreDoc[] scoreDocs = new ScoreDoc[actualSize];
        SearchHit[] hits = new SearchHit[actualSize];
        Set<Integer> usedDocIds = new HashSet<>();
        for (int i = 0; i < actualSize; i++) {
            float score = randomFloat();
            maxScore = max(maxScore, score);
            int docId = randomValueOtherThanMany(usedDocIds::contains, () -> between(0, IndexWriter.MAX_DOCS));
            usedDocIds.add(docId);

            Map<String, SearchHitField> searchHitFields = new HashMap<>();
            if (testInstancesLookSortedByField) {
                Object[] fields = new Object[testInstancesSortFields.length];
                for (int f = 0; f < testInstancesSortFields.length; f++) {
                    fields[f] = randomOfType(testInstancesSortFields[f].getType());
                }
                scoreDocs[i] = new FieldDoc(docId, score, fields);
            } else {
                scoreDocs[i] = new ScoreDoc(docId, score);
            }
            hits[i] = new SearchHit(docId, Integer.toString(i), new Text("test"), searchHitFields);
            hits[i].score(score);
        }
        int totalHits = between(actualSize, 500000);
        SearchHits searchHits = new SearchHits(hits, totalHits, maxScore);

        TopDocs topDocs;
        Arrays.sort(scoreDocs, scoreDocComparator());
        if (testInstancesLookSortedByField) {
            topDocs = new TopFieldDocs(totalHits, scoreDocs, testInstancesSortFields, maxScore);
        } else {
            topDocs = new TopDocs(totalHits, scoreDocs, maxScore);
        }

        return new InternalTopHits(name, from, requestedSize, topDocs, searchHits, pipelineAggregators, metaData);
    }

    private Object randomOfType(SortField.Type type) {
        switch (type) {
        case CUSTOM:
            throw new UnsupportedOperationException();
        case DOC:
            return between(0, IndexWriter.MAX_DOCS);
        case DOUBLE:
            return randomDouble();
        case FLOAT:
            return randomFloat();
        case INT:
            return randomInt();
        case LONG:
            return randomLong();
        case REWRITEABLE:
            throw new UnsupportedOperationException();
        case SCORE:
            return randomFloat();
        case STRING:
            return new BytesRef(randomAsciiOfLength(5));
        case STRING_VAL:
            return new BytesRef(randomAsciiOfLength(5));
        default:
            throw new UnsupportedOperationException("Unkown SortField.Type: " + type);
        }
    }

    @Override
    protected void assertReduced(InternalTopHits reduced, List<InternalTopHits> inputs) {
        SearchHits actualHits = reduced.getHits();
        List<Tuple<ScoreDoc, SearchHit>> allHits = new ArrayList<>();
        float maxScore = Float.MIN_VALUE;
        long totalHits = 0;
        for (int input = 0; input < inputs.size(); input++) {
            SearchHits internalHits = inputs.get(input).getHits();
            totalHits += internalHits.getTotalHits();
            maxScore = max(maxScore, internalHits.getMaxScore());
            for (int i = 0; i < internalHits.internalHits().length; i++) {
                ScoreDoc doc = inputs.get(input).getTopDocs().scoreDocs[i];
                if (testInstancesLookSortedByField) {
                    doc = new FieldDoc(doc.doc, doc.score, ((FieldDoc) doc).fields, input);
                } else {
                    doc = new ScoreDoc(doc.doc, doc.score, input);
                }
                allHits.add(new Tuple<>(doc, internalHits.internalHits()[i]));
            }
        }
        allHits.sort(comparing(Tuple::v1, scoreDocComparator()));
        SearchHit[] expectedHitsHits = new SearchHit[min(inputs.get(0).getSize(), allHits.size())];
        for (int i = 0; i < expectedHitsHits.length; i++) {
            expectedHitsHits[i] = allHits.get(i).v2();
        }
        SearchHits expectedHits = new SearchHits(expectedHitsHits, totalHits, maxScore);
        assertEqualsWithErrorMessageFromXContent(expectedHits, actualHits);
    }

    @Override
    protected Reader<InternalTopHits> instanceReader() {
        return InternalTopHits::new;
    }

    private SortField[] randomSortFields() {
        SortField[] sortFields = new SortField[between(1, 5)];
        Set<String> usedSortFields = new HashSet<>();
        for (int i = 0; i < sortFields.length; i++) {
            String sortField = randomValueOtherThanMany(usedSortFields::contains, () -> randomAsciiOfLength(5));
            usedSortFields.add(sortField);
            SortField.Type type = randomValueOtherThanMany(t -> t == SortField.Type.CUSTOM || t == SortField.Type.REWRITEABLE,
                    () -> randomFrom(SortField.Type.values()));
            sortFields[i] = new SortField(sortField, type);
        }
        return sortFields;
    }

    private Comparator<ScoreDoc> scoreDocComparator() {
        return innerScoreDocComparator().thenComparing(s -> s.shardIndex);
    }

    private Comparator<ScoreDoc> innerScoreDocComparator() {
        if (testInstancesLookSortedByField) {
            // Values passed to getComparator shouldn't matter
            @SuppressWarnings("rawtypes")
            FieldComparator[] comparators = new FieldComparator[testInstancesSortFields.length];
            for (int i = 0; i < testInstancesSortFields.length; i++) {
                try {
                    comparators[i] = testInstancesSortFields[i].getComparator(0, 0);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return (lhs, rhs) -> {
                FieldDoc l = (FieldDoc) lhs;
                FieldDoc r = (FieldDoc) rhs;
                int i = 0;
                while (i < l.fields.length) {
                    @SuppressWarnings("unchecked")
                    int c = comparators[i].compareValues(l.fields[i], r.fields[i]);
                    if (c != 0) {
                        return c;
                    }
                    i++;
                }
                return 0;
            };
        } else {
            Comparator<ScoreDoc> comparator = comparing(d -> d.score);
            return comparator.reversed();
        }
    }
}
