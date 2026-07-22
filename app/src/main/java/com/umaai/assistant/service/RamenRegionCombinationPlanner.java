package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scenario 14: choose exactly three regions from the current candidate pool. */
public final class RamenRegionCombinationPlanner {
    private RamenRegionCombinationPlanner() {}

    public static String buildSummary(JSONObject summary, String regionCatalogRaw,
                                      String resourceCatalogRaw) {
        if (summary == null || regionCatalogRaw == null || resourceCatalogRaw == null) return "";
        JSONObject ramen = summary.optJSONObject("ramen");
        if (ramen == null) return "";
        try {
            JSONObject regionCatalog = new JSONObject(regionCatalogRaw);
            JSONObject resourceCatalog = new JSONObject(resourceCatalogRaw);
            CandidatePool pool = candidatePool(summary, ramen, regionCatalog);
            if (pool.ids.size() < 3) return "";

            Map<Integer, Region> regions = readRegions(regionCatalog, resourceCatalog);
            List<Integer> valid = new ArrayList<>();
            for (Integer id : pool.ids) if (regions.containsKey(id)) valid.add(id);
            if (valid.size() < 3) return "";

            int[] inventory = readInventory(ramen);
            int special = Math.max(0, ramen.optInt("special_feeling_num", 0));
            List<Combination> combinations = enumerate(valid, regions, inventory, special);
            if (combinations.isEmpty()) return "";
            Collections.sort(combinations, COMPARATOR);

            StringBuilder out = new StringBuilder("地区多选三[")
                    .append(pool.source).append(",候选").append(valid.size())
                    .append(",组合").append(combinations.size()).append("]:");
            int limit = Math.min(3, combinations.size());
            for (int i = 0; i < limit; i++) {
                Combination c = combinations.get(i);
                out.append(i == 0 ? " " : "；")
                        .append(i + 1).append(".").append(c.names)
                        .append(" 可做").append(c.craftableCount).append("/3")
                        .append(" 缺口").append(c.totalDeficit);
                if (c.specialNeeded > 0) out.append(" 万能").append(c.specialNeeded);
            }
            out.append("（仅资源排序；地区效果/训练价值未计分）");
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static CandidatePool candidatePool(JSONObject summary, JSONObject ramen,
                                               JSONObject catalog) {
        JSONArray runtime = ramen.optJSONArray("selectable_region_ids");
        if (runtime != null && runtime.length() >= 3) {
            return new CandidatePool(uniqueInts(runtime), "实时数据");
        }
        int turn = summary.optInt("turn", -1);
        int selectType = -1;
        JSONArray phases = catalog.optJSONArray("selection_phases");
        if (phases != null) {
            for (int i = 0; i < phases.length(); i++) {
                JSONObject phase = phases.optJSONObject(i);
                if (phase != null && phase.optInt("turn", -2) == turn) {
                    selectType = phase.optInt("region_select_type", -1);
                    break;
                }
            }
        }
        if (selectType < 0) return new CandidatePool(Collections.emptyList(), "无");
        List<Integer> ids = new ArrayList<>();
        JSONArray rows = catalog.optJSONArray("regions");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && row.optInt("region_select_type", -1) == selectType) {
                    ids.add(row.optInt("region_id", -1));
                }
            }
        }
        return new CandidatePool(ids, "阶段目录回退");
    }

    private static Map<Integer, Region> readRegions(JSONObject catalog, JSONObject resources) {
        Map<Integer, int[]> costs = new HashMap<>();
        JSONArray recipes = resources.optJSONArray("recipes");
        if (recipes != null) {
            for (int i = 0; i < recipes.length(); i++) {
                JSONObject row = recipes.optJSONObject(i);
                JSONObject cost = row == null ? null : row.optJSONObject("cost");
                if (cost != null) costs.put(row.optInt("region_id"),
                        new int[]{0, cost.optInt("1"), cost.optInt("2"), cost.optInt("3")});
            }
        }
        Map<Integer, Region> result = new HashMap<>();
        JSONArray rows = catalog.optJSONArray("regions");
        if (rows == null) return result;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            int id = row.optInt("region_id", -1);
            int[] cost = costs.get(id);
            if (id > 0 && cost != null) result.put(id, new Region(id,
                    row.optString("name_ja", "编号" + id), cost));
        }
        return result;
    }

    private static List<Combination> enumerate(List<Integer> ids, Map<Integer, Region> regions,
                                               int[] inventory, int special) {
        List<Combination> result = new ArrayList<>();
        for (int a = 0; a < ids.size() - 2; a++) {
            for (int b = a + 1; b < ids.size() - 1; b++) {
                for (int c = b + 1; c < ids.size(); c++) {
                    Region[] selected = {regions.get(ids.get(a)), regions.get(ids.get(b)),
                            regions.get(ids.get(c))};
                    result.add(new Combination(selected, inventory, special));
                }
            }
        }
        return result;
    }

    private static final Comparator<Combination> COMPARATOR = (a, b) -> {
        int cmp = Integer.compare(b.craftableCount, a.craftableCount);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(a.totalDeficit, b.totalDeficit);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(a.maxDeficit, b.maxDeficit);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(a.specialNeeded, b.specialNeeded);
        if (cmp != 0) return cmp;
        return a.idKey.compareTo(b.idKey);
    };

    private static int[] readInventory(JSONObject ramen) {
        int[] result = new int[4];
        JSONArray sozai = ramen.optJSONArray("sozai");
        if (sozai != null) for (int id = 1; id <= 3; id++) result[id] = sozai.optInt(id - 1);
        return result;
    }

    private static List<Integer> uniqueInts(JSONArray array) {
        Set<Integer> values = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            int value = array.optInt(i, -1);
            if (value > 0) values.add(value);
        }
        return new ArrayList<>(values);
    }

    private static final class CandidatePool {
        final List<Integer> ids;
        final String source;
        CandidatePool(List<Integer> ids, String source) { this.ids = ids; this.source = source; }
    }

    private static final class Region {
        final int id;
        final String name;
        final int[] cost;
        Region(int id, String name, int[] cost) {
            this.id = id; this.name = name; this.cost = cost;
        }
    }

    private static final class Combination {
        final String names;
        final String idKey;
        final int craftableCount;
        final int totalDeficit;
        final int maxDeficit;
        final int specialNeeded;
        Combination(Region[] regions, int[] inventory, int special) {
            StringBuilder labels = new StringBuilder();
            StringBuilder ids = new StringBuilder();
            int craftable = 0, deficitSum = 0, worst = 0, universal = 0;
            for (Region region : regions) {
                if (labels.length() > 0) { labels.append("+"); ids.append("-"); }
                labels.append(region.name).append("#").append(region.id);
                ids.append(String.format("%02d", region.id));
                int deficit = 0;
                for (int id = 1; id <= 3; id++) deficit += Math.max(0, region.cost[id] - inventory[id]);
                deficitSum += deficit;
                worst = Math.max(worst, deficit);
                if (deficit <= Math.min(2, special)) { craftable++; universal += deficit; }
            }
            names = labels.toString(); idKey = ids.toString(); craftableCount = craftable;
            totalDeficit = deficitSum; maxDeficit = worst; specialNeeded = universal;
        }

    }
}
