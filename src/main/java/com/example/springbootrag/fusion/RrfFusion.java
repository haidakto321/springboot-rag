package com.example.springbootrag.fusion;

import com.example.springbootrag.model.SearchHit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reciprocal Rank Fusion: score = sum over lists of 1/(k + rank). */
public class RrfFusion {

    private final int k;

    public RrfFusion(int k) {
        this.k = k;
    }

    public List<SearchHit> fuse(List<List<SearchHit>> rankedLists, int topK) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, Integer> bestRank = new HashMap<>();
        Map<Long, SearchHit> byId = new HashMap<>();

        for (List<SearchHit> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                SearchHit hit = list.get(rank);
                scores.merge(hit.id(), 1.0 / (k + rank + 1), Double::sum);
                bestRank.merge(hit.id(), rank, Math::min);
                byId.putIfAbsent(hit.id(), hit);
            }
        }

        // Primary sort: score descending. Tiebreak: best (lowest) rank, then id descending.
        Comparator<Map.Entry<Long, Double>> comparator = Map.Entry.<Long, Double>comparingByValue()
                .reversed()
                .thenComparingInt(e -> bestRank.get(e.getKey()))
                .thenComparing(Comparator.<Map.Entry<Long, Double>, Long>comparing(Map.Entry::getKey).reversed());

        return scores.entrySet().stream()
                .sorted(comparator)
                .limit(topK)
                .map(e -> {
                    SearchHit h = byId.get(e.getKey());
                    return new SearchHit(h.id(), h.docId(), h.chunkIndex(), h.content(), e.getValue());
                })
                .toList();
    }
}
