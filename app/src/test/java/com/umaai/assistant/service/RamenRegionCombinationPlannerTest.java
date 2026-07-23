package com.umaai.assistant.service;

import org.json.JSONObject;

/**
 * JVM unit tests for RamenRegionCombinationPlanner.
 * Standalone main() so the suite can run without a test framework:
 * any failed expectation throws AssertionError.
 */
public final class RamenRegionCombinationPlannerTest {

    private static String regionCatalog() {
        StringBuilder regions = new StringBuilder();
        String[] names = {"札幌", "函館", "福島", "新潟", "東京"};
        for (int i = 1; i <= 5; i++) {
            if (i > 1) regions.append(',');
            regions.append("{\"region_id\":").append(i)
                    .append(",\"region_select_type\":1,\"name_ja\":\"").append(names[i - 1]).append("\"}");
        }
        return "{\"selection_phases\":[{\"turn\":3,\"region_select_type\":1}],"
                + "\"regions\":[" + regions + "]}";
    }

    /** cost per region id: {normalCostOfType1} */
    private static String resourceCatalog(int c1, int c2, int c3, int c4, int c5) {
        int[] costs = {c1, c2, c3, c4, c5};
        StringBuilder recipes = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            if (i > 1) recipes.append(',');
            recipes.append("{\"region_id\":").append(i)
                    .append(",\"cost\":{\"1\":").append(costs[i - 1])
                    .append(",\"2\":0,\"3\":0}}");
        }
        return "{\"recipes\":[" + recipes + "]}";
    }

    private static String summary(String ramenFields) {
        return "{\"turn\":3,\"ramen\":{\"selected_region_ids\":[],"
                + "\"selectable_region_ids_derived\":[1,2,3,4,5],"
                + "\"selectable_region_ids_source\":\"mdb_pool_minus_all_selected_derivation\","
                + ramenFields + "}}";
    }

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        System.out.println("ok - " + name);
    }

    public static void main(String[] args) {
        // 1. Normal-material reuse: 5 in stock, every bowl costs 5 of type 1.
        //    Each bowl alone is craftable, but in a row only ONE is.
        String out = RamenRegionCombinationPlanner.buildSummary(
                new JSONObject(summary("\"sozai\":[5,0,0],\"special_feeling_num\":0")),
                regionCatalog(), resourceCatalog(5, 5, 5, 5, 5));
        check(out.contains("连做1/3"), "normal inventory consumed across bowls (连做1/3): " + out);
        check(out.contains("单做3/3"), "solo judgement independent (单做3/3): " + out);

        // 2. Universal reuse: special=2, every bowl missing 2 -> only one bowl
        //    can be covered in a row.
        out = RamenRegionCombinationPlanner.buildSummary(
                new JSONObject(summary("\"sozai\":[0,0,0],\"special_feeling_num\":2")),
                regionCatalog(), resourceCatalog(2, 2, 2, 2, 2));
        check(out.contains("连做1/3"), "universal stock consumed across bowls (连做1/3): " + out);
        check(out.contains("万能2"), "universal usage counted once (万能2): " + out);

        // 3. Derived pool is consumed when source label matches.
        check(out.contains("插件MDB推导"), "derived pool consumed (插件MDB推导): " + out);

        // 4. Derived pool ignored when source label does not match.
        String badSource = summary("\"sozai\":[99,99,99],\"special_feeling_num\":9")
                .replace("mdb_pool_minus_all_selected_derivation", "unknown");
        out = RamenRegionCombinationPlanner.buildSummary(
                new JSONObject(badSource), regionCatalog(), resourceCatalog(1, 1, 1, 1, 1));
        check(out.contains("阶段目录回退"), "derived pool rejected on wrong source: " + out);

        // 5. Runtime-native pool wins over derived pool.
        String runtimeFirst = summary("\"sozai\":[99,99,99],\"special_feeling_num\":9,"
                + "\"selectable_region_ids\":[1,2,3]");
        out = RamenRegionCombinationPlanner.buildSummary(
                new JSONObject(runtimeFirst), regionCatalog(), resourceCatalog(1, 1, 1, 1, 1));
        check(out.contains("实时数据"), "runtime pool has priority: " + out);

        // 6. Order enumeration finds the best sequence: region1 cost 5,
        //    regions 2-5 cost 0; inventory 5 -> all three craftable only if
        //    the expensive bowl is ordered correctly (any order works here,
        //    count must be 3 with the greedy-free permutation search).
        out = RamenRegionCombinationPlanner.buildSummary(
                new JSONObject(summary("\"sozai\":[5,0,0],\"special_feeling_num\":0")),
                regionCatalog(), resourceCatalog(5, 0, 0, 0, 0));
        check(out.contains("连做3/3"), "permutation search reaches 连做3/3: " + out);

        System.out.println("ALL TESTS PASSED");
    }
}
