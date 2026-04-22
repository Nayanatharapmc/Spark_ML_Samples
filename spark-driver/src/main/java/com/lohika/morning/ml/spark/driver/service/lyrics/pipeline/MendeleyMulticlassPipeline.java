package com.lohika.morning.ml.spark.driver.service.lyrics.pipeline;

import com.lohika.morning.ml.spark.driver.service.lyrics.GenrePrediction;
import java.io.Serializable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.spark.ml.Pipeline;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.ml.PipelineStage;
import org.apache.spark.ml.classification.LogisticRegression;
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator;
import org.apache.spark.ml.feature.HashingTF;
import org.apache.spark.ml.feature.IDF;
import org.apache.spark.ml.feature.RegexTokenizer;
import org.apache.spark.ml.feature.StopWordsRemover;
import org.apache.spark.ml.feature.StringIndexer;
import org.apache.spark.ml.feature.StringIndexerModel;
import org.apache.spark.ml.linalg.DenseVector;
import org.apache.spark.ml.param.ParamMap;
import org.apache.spark.ml.tuning.CrossValidator;
import org.apache.spark.ml.tuning.CrossValidatorModel;
import org.apache.spark.ml.tuning.ParamGridBuilder;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.functions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("MendeleyMulticlassPipeline")
public class MendeleyMulticlassPipeline extends CommonLyricsPipeline {

    @Value("${lyrics.merged.csv.path:${lyrics.mendeley.csv.path}}")
    private String lyricsMergedCsvPath;

    private Map<String, Object> modelStatistics = new LinkedHashMap<>();

    @Override
    public CrossValidatorModel classify() {
        Dataset<Row> fullDataset = readMendeleyLyrics();

        Dataset<Row>[] splitDataFrames = fullDataset.randomSplit(new double[]{0.8D, 0.2D}, 42L);
        Dataset<Row> trainingSet = splitDataFrames[0].cache();
        Dataset<Row> testSet = splitDataFrames[1].cache();

        long trainRows = trainingSet.count();
        long testRows = testSet.count();

        StringIndexer labelIndexer = new StringIndexer()
                .setInputCol("genre")
            .setOutputCol("label")
            .setHandleInvalid("keep");

        RegexTokenizer tokenizer = new RegexTokenizer()
                .setInputCol("lyrics")
                .setOutputCol("words")
                .setPattern("\\W+");

        StopWordsRemover stopWordsRemover = new StopWordsRemover()
                .setInputCol("words")
                .setOutputCol("filteredWords");

        HashingTF tf = new HashingTF()
                .setInputCol("filteredWords")
                .setOutputCol("rawFeatures")
                .setNumFeatures(1 << 18);

        IDF idf = new IDF()
                .setInputCol("rawFeatures")
                .setOutputCol("features");

        LogisticRegression logisticRegression = new LogisticRegression()
                .setLabelCol("label")
                .setFeaturesCol("features")
                .setMaxIter(100)
                .setRegParam(0.01D);

        Pipeline pipeline = new Pipeline().setStages(new PipelineStage[]{
                labelIndexer,
                tokenizer,
                stopWordsRemover,
                tf,
                idf,
            logisticRegression
        });

        ParamMap[] paramGrid = new ParamGridBuilder().build();

        CrossValidator crossValidator = new CrossValidator()
                .setEstimator(pipeline)
                .setEvaluator(new MulticlassClassificationEvaluator()
                        .setLabelCol("label")
                        .setPredictionCol("prediction")
                        .setMetricName("accuracy"))
                .setEstimatorParamMaps(paramGrid)
                .setNumFolds(2);

        CrossValidatorModel model = crossValidator.fit(trainingSet);
        saveModel(model, getModelDirectory());

        PipelineModel bestModel = (PipelineModel) model.bestModel();
        Dataset<Row> predictions = bestModel.transform(testSet);

        StringIndexerModel fittedLabelIndexer = (StringIndexerModel) bestModel.stages()[0];
        String[] labels = fittedLabelIndexer.labels();

        MulticlassClassificationEvaluator evaluator = new MulticlassClassificationEvaluator()
                .setLabelCol("label")
                .setPredictionCol("prediction");

        modelStatistics = new LinkedHashMap<>();
        modelStatistics.put("Train rows", trainRows);
        modelStatistics.put("Test rows", testRows);
        modelStatistics.put("Classes", Arrays.asList(labels));
        modelStatistics.put("Accuracy", evaluator.setMetricName("accuracy").evaluate(predictions));
        modelStatistics.put("F1", evaluator.setMetricName("f1").evaluate(predictions));
        modelStatistics.put("Weighted precision", evaluator.setMetricName("weightedPrecision").evaluate(predictions));
        modelStatistics.put("Weighted recall", evaluator.setMetricName("weightedRecall").evaluate(predictions));

        printModelStatistics(modelStatistics);

        return model;
    }

    @Override
    public GenrePrediction predict(String unknownLyrics) {
        Dataset<Row> unknownLyricsDataset = sparkSession.createDataFrame(
                Arrays.asList(new LyricsInput("unknown", "unknown", unknownLyrics)),
                LyricsInput.class);

        CrossValidatorModel model = CrossValidatorModel.load(getModelDirectory());
        PipelineModel bestModel = (PipelineModel) model.bestModel();

        Dataset<Row> predictionsDataset = bestModel.transform(unknownLyricsDataset);
        Row predictionRow = predictionsDataset.first();

        Double predictionIndex = predictionRow.getAs("prediction");
        DenseVector probability = predictionRow.getAs("probability");
        String[] labels = ((StringIndexerModel) bestModel.stages()[0]).labels();

        String predictedGenre = "unknown";
        if (predictionIndex != null) {
            int index = predictionIndex.intValue();
            if (index >= 0 && index < labels.length) {
                predictedGenre = labels[index];
            }
        }

        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (int i = 0; i < labels.length && i < probability.size(); i++) {
            probabilities.put(labels[i], probability.apply(i));
        }

        return new GenrePrediction(predictedGenre, probabilities);
    }

    @Override
    public Map<String, Object> getModelStatistics(CrossValidatorModel model) {
        if (modelStatistics == null || modelStatistics.isEmpty()) {
            return super.getModelStatistics(model);
        }

        return modelStatistics;
    }

    @Override
    protected String getModelDirectory() {
        return getLyricsModelDirectoryPath() + "/mendeley-8class-logreg/";
    }

    private Dataset<Row> readMendeleyLyrics() {
        Dataset<Row> raw = sparkSession.read()
                .option("header", "true")
                .option("multiLine", "true")
                .option("escape", "\"")
            .csv(lyricsMergedCsvPath);

        Dataset<Row> normalizedColumns = raw;
        for (String column : raw.columns()) {
            String normalizedColumn = column
                .replace("\uFEFF", "")
                .replace("ï»¿", "")
                .trim();

            if (!normalizedColumn.equals(column)) {
            normalizedColumns = normalizedColumns.withColumnRenamed(column, normalizedColumn);
            }
        }

        Dataset<Row> filtered = normalizedColumns
                .select("artist_name", "track_name", "release_date", "genre", "lyrics")
            .filter(normalizedColumns.col("lyrics").isNotNull())
            .filter(normalizedColumns.col("genre").isNotNull())
            .withColumn("genre", functions.lower(functions.trim(normalizedColumns.col("genre"))))
            .withColumn("lyrics", functions.trim(normalizedColumns.col("lyrics")))
            .filter(functions.length(normalizedColumns.col("lyrics")).gt(0))
            .filter(normalizedColumns.col("genre").isin("pop", "country", "blues", "jazz", "reggae", "rock", "hip hop", "soul"))
                .cache();

        filtered.count();

        return filtered;
    }

    public static class LyricsInput implements Serializable {
        private String id;
        private String genre;
        private String lyrics;

        public LyricsInput() {
        }

        public LyricsInput(String id, String genre, String lyrics) {
            this.id = id;
            this.genre = genre;
            this.lyrics = lyrics;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getGenre() {
            return genre;
        }

        public void setGenre(String genre) {
            this.genre = genre;
        }

        public String getLyrics() {
            return lyrics;
        }

        public void setLyrics(String lyrics) {
            this.lyrics = lyrics;
        }
    }
}