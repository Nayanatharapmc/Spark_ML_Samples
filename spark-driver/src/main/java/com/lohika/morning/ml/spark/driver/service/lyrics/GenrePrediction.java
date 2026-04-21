package com.lohika.morning.ml.spark.driver.service.lyrics;

import java.util.LinkedHashMap;
import java.util.Map;

public class GenrePrediction {

    private String predictedGenre;
    private Map<String, Double> probabilities;

    public GenrePrediction(String genre, Double metalProbability, Double popProbability) {
        this.predictedGenre = genre;
        Map<String, Double> binaryProbabilities = new LinkedHashMap<>();
        if (metalProbability != null) {
            binaryProbabilities.put("metal", metalProbability);
        }
        if (popProbability != null) {
            binaryProbabilities.put("pop", popProbability);
        }
        this.probabilities = binaryProbabilities;
    }

    public GenrePrediction(String genre) {
        this.predictedGenre = genre;
        this.probabilities = new LinkedHashMap<>();
    }

    public GenrePrediction(String predictedGenre, Map<String, Double> probabilities) {
        this.predictedGenre = predictedGenre;
        this.probabilities = probabilities;
    }

    public String getPredictedGenre() {
        return predictedGenre;
    }

    public Map<String, Double> getProbabilities() {
        return probabilities;
    }
}
