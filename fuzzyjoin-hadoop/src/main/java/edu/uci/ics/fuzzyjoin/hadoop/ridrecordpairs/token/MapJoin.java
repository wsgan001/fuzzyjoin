/**
 * Copyright 2010-2011 The Regents of the University of California
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS"; BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under
 * the License.
 * 
 * Author: Rares Vernica <rares (at) ics.uci.edu>
 */

package edu.uci.ics.fuzzyjoin.hadoop.ridrecordpairs.token;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;

import edu.uci.ics.fuzzyjoin.FuzzyJoinConfig;
import edu.uci.ics.fuzzyjoin.FuzzyJoinUtil;
import edu.uci.ics.fuzzyjoin.hadoop.FuzzyJoinDriver;
import edu.uci.ics.fuzzyjoin.hadoop.IntPairWritable;
import edu.uci.ics.fuzzyjoin.hadoop.ridrecordpairs.ValueJoin;
import edu.uci.ics.fuzzyjoin.similarity.SimilarityFilters;
import edu.uci.ics.fuzzyjoin.similarity.SimilarityFiltersFactory;
import edu.uci.ics.fuzzyjoin.tokenizer.Tokenizer;
import edu.uci.ics.fuzzyjoin.tokenizer.TokenizerFactory;
import edu.uci.ics.fuzzyjoin.tokenorder.TokenLoad;
import edu.uci.ics.fuzzyjoin.tokenorder.TokenRank;
import edu.uci.ics.fuzzyjoin.tokenorder.TokenRankFrequency;

/**
 * @author rares
 * 
 *         KEY1: Unused
 * 
 *         VALUE1: Record
 * 
 *         KEY2: Token, Relation
 * 
 *         VALUE2: RID, Tokens
 * 
 */
public class MapJoin extends MapReduceBase implements
        Mapper<Object, Text, IntPairWritable, ValueJoin> {

    private int[][] dataColumns = new int[2][];
    private final ValueJoin outputValue = new ValueJoin();
    private SimilarityFilters similarityFilters;
    private Tokenizer tokenizer;
    private final TokenRank tokenRank = new TokenRankFrequency();
    private final IntPairWritable outputKey = new IntPairWritable();
    private String suffixSecond;

    @Override
    public void configure(JobConf job) {
        //
        // set Tokenizer and SimilarityFilters
        //
        tokenizer = TokenizerFactory.getTokenizer(job.get(
                FuzzyJoinConfig.TOKENIZER_PROPERTY,
                FuzzyJoinConfig.TOKENIZER_VALUE),
                FuzzyJoinConfig.WORD_SEPARATOR_REGEX,
                FuzzyJoinConfig.TOKEN_SEPARATOR);
        String similarityName = job.get(
                FuzzyJoinConfig.SIMILARITY_NAME_PROPERTY,
                FuzzyJoinConfig.SIMILARITY_NAME_VALUE);
        float similarityThreshold = job.getFloat(
                FuzzyJoinConfig.SIMILARITY_THRESHOLD_PROPERTY,
                FuzzyJoinConfig.SIMILARITY_THRESHOLD_VALUE);
        similarityFilters = SimilarityFiltersFactory.getSimilarityFilters(
                similarityName, similarityThreshold);
        //
        // set TokenRank
        //
        Path tokensPath;
        try {
            Path[] cache = DistributedCache.getLocalCacheFiles(job);
            if (cache == null) {
                tokensPath = new Path(job
                        .get(FuzzyJoinConfig.DATA_TOKENS_PROPERTY));
            } else {
                tokensPath = cache[0];
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        TokenLoad tokenLoad = new TokenLoad(tokensPath.toString(), tokenRank);
        tokenLoad.loadTokenRank();
        //
        // set dataColumn
        //
        dataColumns[0] = FuzzyJoinUtil.getDataColumns(job.get(
                FuzzyJoinConfig.RECORD_DATA_PROPERTY,
                FuzzyJoinConfig.RECORD_DATA_VALUE));
        dataColumns[1] = FuzzyJoinUtil.getDataColumns(job.get(
                FuzzyJoinConfig.RECORD_DATA1_PROPERTY,
                FuzzyJoinConfig.RECORD_DATA1_VALUE));
        //
        // get suffix for second relation
        //
        suffixSecond = job.get(FuzzyJoinDriver.DATA_SUFFIX_INPUT_PROPERTY, "")
                .split(FuzzyJoinDriver.SEPSARATOR_REGEX)[1];
    }

    public void map(Object unused, Text inputValue,
            OutputCollector<IntPairWritable, ValueJoin> output,
            Reporter reporter) throws IOException {
        //
        // get RID and Tokens
        //
        String splits[] = inputValue.toString().split(
                FuzzyJoinConfig.RECORD_SEPARATOR_REGEX);

        int relation = 0;
        if (reporter.getInputSplit().toString().contains(suffixSecond)) {
            relation = 1;
        }
        int rid = Integer.valueOf(splits[FuzzyJoinConfig.RECORD_KEY]);
        List<String> tokens = tokenizer.tokenize(FuzzyJoinUtil.getData(
                inputValue.toString().split(
                        FuzzyJoinConfig.RECORD_SEPARATOR_REGEX),
                dataColumns[relation], FuzzyJoinConfig.TOKEN_SEPARATOR));
        Collection<Integer> tokensRanked = tokenRank.getTokenRanks(tokens);
        int prefixLength = similarityFilters.getPrefixLength(tokensRanked
                .size());
        int position = 0;

        outputKey.setSecond(relation);
        outputValue.setRelation(relation);
        outputValue.setRID(rid);
        outputValue.setTokens(tokensRanked);
        outputValue.setLength(tokens.size());
        outputValue.setRecord(inputValue);
        for (Integer token : tokensRanked) {
            if (position < prefixLength) {
                outputKey.setFirst(token);
                //
                // collect
                //
                output.collect(outputKey, outputValue);
            } else {
                break;
            }
            position++;
        }
    }
}
