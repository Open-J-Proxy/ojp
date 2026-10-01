package org.openjproxy.grpc.server.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Allocates server-configured database connection budgets across local pools.
 */
public final class DatabaseConnectionBudgetManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionBudgetManager.class);
    private static final String PROPERTY_PREFIX = "ojp.server.databaseBudgets.";
    private static final String JDBC_URL_PATTERN_SUFFIX = ".match.jdbcUrlPattern";
    private static final String MAX_CONNECTIONS_SUFFIX = ".maxTotalConnections";
    private static final String RESERVE_CONNECTIONS_SUFFIX = ".reserveConnections";
    private static final String USER_PRIORITY_SUFFIX = ".priorities.username.";
    private static final String WEIGHT_SUFFIX = ".weight";
    private static final String CONFIG_FILE_NAME = "ojp.properties";

    private static DatabaseConnectionBudgetManager instance;

    private final List<Budget> budgets;
    private final Map<String, Pool> pools = new HashMap<>();

    public DatabaseConnectionBudgetManager(Properties properties) {
        this.budgets = parseBudgets(properties == null ? new Properties() : properties);
    }

    public static synchronized DatabaseConnectionBudgetManager getInstance() {
        if (instance == null) {
            instance = new DatabaseConnectionBudgetManager(loadBudgetProperties(
                    Path.of("."), DatabaseConnectionBudgetManager.class.getClassLoader(), System.getProperties()));
        }
        return instance;
    }

    static Properties loadBudgetProperties(Path serverDirectory, ClassLoader classLoader, Properties systemProperties) {
        Properties properties = new Properties();
        Path configFile = serverDirectory.resolve(CONFIG_FILE_NAME);
        if (Files.isRegularFile(configFile)) {
            loadProperties(properties, () -> Files.newInputStream(configFile), configFile.toString());
        } else {
            InputStream resource = classLoader.getResourceAsStream(CONFIG_FILE_NAME);
            if (resource != null) {
                loadProperties(properties, () -> resource, "classpath:" + CONFIG_FILE_NAME);
            }
        }
        properties.putAll(systemProperties);
        return properties;
    }

    private static void loadProperties(Properties properties, InputStreamSupplier inputStreamSupplier, String source) {
        try (InputStream inputStream = inputStreamSupplier.get()) {
            properties.load(inputStream);
            log.info("Loaded database budget properties from {}", source);
        } catch (IOException e) {
            log.warn("Could not load database budget properties from {}", source, e);
        }
    }

    public synchronized Registration registerPool(String poolId, String jdbcUrl, String username,
                                                   int requestedMax, int requestedMin, boolean resizable) {
        if (poolId == null || poolId.isBlank() || requestedMax < 1 || requestedMin < 0) {
            throw new IllegalArgumentException("A pool id and valid requested pool sizes are required");
        }
        if (pools.containsKey(poolId)) {
            throw new IllegalArgumentException("A database budget pool is already registered: " + poolId);
        }

        Budget budget = findBudget(jdbcUrl);
        Registration registration = new Registration(poolId, budget, requestedMax,
                Math.min(requestedMin, requestedMax));
        if (budget == null) {
            return registration;
        }

        Pool pool = new Pool(registration, username == null ? "" : username, resizable);
        pools.put(poolId, pool);
        try {
            rebalance(budget);
            log.info("Database budget '{}' assigned pool '{}' maxPoolSize={}", budget.name, poolId, pool.allocatedMax);
            return registration;
        } catch (RuntimeException e) {
            pools.remove(poolId);
            try {
                rebalance(budget);
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    public synchronized void attachPool(Registration registration, PoolResizer resizer,
                                        int configuredMaximum, int configuredMinimum) {
        if (registration == null || registration.budget == null) {
            return;
        }
        Pool pool = pools.get(registration.poolId);
        if (pool == null) {
            return;
        }
        if (resizer == null) {
            throw new IllegalArgumentException("A pool resizer is required for a budgeted pool");
        }
        pool.resizer = resizer;
        if (configuredMaximum != pool.allocatedMax || configuredMinimum != pool.allocatedMin) {
            if (!pool.resizable) {
                throw new IllegalStateException("Pool '" + registration.poolId
                        + "' changed allocation before creation and its provider cannot resize it");
            }
            resize(pool, pool.allocatedMax, pool.allocatedMin);
        }
    }

    public synchronized void unregisterPool(Registration registration) {
        if (registration == null || registration.budget == null) {
            return;
        }
        unregisterPool(registration.poolId);
    }

    public synchronized void unregisterPool(String poolId) {
        Pool removed = pools.remove(poolId);
        if (removed != null) {
            rebalance(removed.registration.budget);
        }
    }

    public synchronized int getMaximumPoolSize(String poolId, int defaultValue) {
        Pool pool = pools.get(poolId);
        return pool == null ? defaultValue : pool.allocatedMax;
    }

    public synchronized int getMinimumIdle(String poolId, int defaultValue) {
        Pool pool = pools.get(poolId);
        return pool == null ? defaultValue : pool.allocatedMin;
    }

    private void rebalance(Budget budget) {
        List<Pool> members = pools.values().stream()
                .filter(pool -> pool.registration.budget == budget)
                .sorted(Comparator.comparing(pool -> pool.registration.poolId))
                .collect(Collectors.toList());
        if (members.isEmpty()) {
            return;
        }

        int capacity = budget.maxConnections - budget.reserveConnections;
        validatePoolCapacity(budget, capacity, members.size());
        Map<Pool, Integer> allocations = allocate(members, capacity, budget);
        keepFixedPoolsAtCurrentAllocation(members, allocations);
        validateFixedPoolAllocations(budget, members, allocations);
        resizePools(members, allocations, false);
        resizePools(members, allocations, true);
        updatePoolAllocations(members, allocations);
    }

    private void validatePoolCapacity(Budget budget, int capacity, int poolCount) {
        if (capacity < poolCount) {
            throw new IllegalStateException("Database budget '" + budget.name + "' has " + capacity
                    + " usable connections for " + poolCount + " pools; each pool requires at least one");
        }
    }

    private void keepFixedPoolsAtCurrentAllocation(List<Pool> members, Map<Pool, Integer> allocations) {
        for (Pool pool : members) {
            int target = allocations.get(pool);
            if (pool.resizer != null && !pool.resizable && target > pool.allocatedMax) {
                allocations.put(pool, pool.allocatedMax);
            }
        }
    }

    private void validateFixedPoolAllocations(Budget budget, List<Pool> members, Map<Pool, Integer> allocations) {
        for (Pool pool : members) {
            int target = allocations.get(pool);
            if (target != pool.allocatedMax && pool.resizer != null && !pool.resizable) {
                throw new IllegalStateException("Pool '" + pool.registration.poolId
                        + "' cannot be resized to enforce database budget '" + budget.name + "'");
            }
        }
    }

    private void resizePools(List<Pool> members, Map<Pool, Integer> allocations, boolean increasing) {
        for (Pool pool : members) {
            int target = allocations.get(pool);
            boolean needsResize = increasing
                    ? target > pool.allocatedMax
                    : target < pool.allocatedMax;
            if (needsResize && pool.resizer != null) {
                resize(pool, target, Math.min(pool.registration.requestedMin, target));
            }
        }
    }

    private void updatePoolAllocations(List<Pool> members, Map<Pool, Integer> allocations) {
        for (Pool pool : members) {
            pool.allocatedMax = Math.min(pool.registration.requestedMax, allocations.get(pool));
            pool.allocatedMin = Math.min(pool.registration.requestedMin, pool.allocatedMax);
        }
    }

    private void resize(Pool pool, int max, int min) {
        if (pool.resizer == null) {
            return;
        }
        try {
            pool.resizer.resize(max, min);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resize pool '" + pool.registration.poolId
                    + "' to max=" + max + ", min=" + min, e);
        }
    }

    private Map<Pool, Integer> allocate(List<Pool> members, int capacity, Budget budget) {
        Map<Pool, Integer> allocations = new LinkedHashMap<>();
        for (Pool pool : members) {
            allocations.put(pool, 0);
        }
        if (totalRequested(members) <= capacity) {
            return allocateRequestedMaximums(members);
        }

        allocateWeightedCapacity(members, capacity, budget, allocations);
        ensureMinimumPoolAllocation(members, allocations);
        return allocations;
    }

    private long totalRequested(List<Pool> members) {
        return members.stream().mapToLong(pool -> pool.registration.requestedMax).sum();
    }

    private Map<Pool, Integer> allocateRequestedMaximums(List<Pool> members) {
        Map<Pool, Integer> allocations = new LinkedHashMap<>();
        for (Pool pool : members) {
            allocations.put(pool, pool.registration.requestedMax);
        }
        return allocations;
    }

    private void allocateWeightedCapacity(List<Pool> members, int capacity, Budget budget,
                                          Map<Pool, Integer> allocations) {
        Map<Pool, Integer> demands = new HashMap<>();
        for (Pool pool : members) {
            demands.put(pool, pool.registration.requestedMax);
        }
        List<Pool> eligible = new ArrayList<>(members);
        int remaining = capacity;
        while (remaining > 0 && !eligible.isEmpty()) {
            Map<Pool, Double> weights = calculatePoolWeights(eligible, budget);
            Pool saturated = findSaturatedPool(eligible, demands, weights, remaining);
            if (saturated != null) {
                remaining = allocateSaturatedPool(saturated, eligible, demands, allocations, remaining);
            } else {
                allocateFractionalShares(eligible, demands, weights, allocations, remaining);
                remaining = 0;
            }
        }
    }

    private Map<Pool, Double> calculatePoolWeights(List<Pool> eligible, Budget budget) {
        Map<String, Integer> eligiblePoolsByUsername = new HashMap<>();
        for (Pool pool : eligible) {
            eligiblePoolsByUsername.merge(pool.username, 1, Integer::sum);
        }
        Map<Pool, Double> weights = new HashMap<>();
        for (Pool pool : eligible) {
            weights.put(pool, budget.usernameWeights.getOrDefault(pool.username, 1.0)
                    / eligiblePoolsByUsername.get(pool.username));
        }
        return weights;
    }

    private Pool findSaturatedPool(List<Pool> eligible, Map<Pool, Integer> demands,
                                   Map<Pool, Double> weights, int remaining) {
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double allocationLevel = remaining / totalWeight;
        return eligible.stream()
                .filter(pool -> demands.get(pool) <= allocationLevel * weights.get(pool))
                .findFirst()
                .orElse(null);
    }

    private int allocateSaturatedPool(Pool pool, List<Pool> eligible, Map<Pool, Integer> demands,
                                      Map<Pool, Integer> allocations, int remaining) {
        int demand = demands.remove(pool);
        allocations.put(pool, allocations.get(pool) + demand);
        eligible.remove(pool);
        return remaining - demand;
    }

    private void allocateFractionalShares(List<Pool> eligible, Map<Pool, Integer> demands,
                                          Map<Pool, Double> weights, Map<Pool, Integer> allocations,
                                          int remaining) {
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double allocationLevel = remaining / totalWeight;
        Map<Pool, Double> fractional = new HashMap<>();
        int assigned = 0;
        for (Pool pool : eligible) {
            double share = allocationLevel * weights.get(pool);
            int whole = (int) Math.floor(share);
            allocations.put(pool, allocations.get(pool) + whole);
            fractional.put(pool, share - whole);
            assigned += whole;
        }
        int remainder = remaining - assigned;
        eligible.sort(Comparator.<Pool>comparingDouble(fractional::get).reversed()
                .thenComparing(pool -> pool.registration.poolId));
        for (Pool pool : eligible) {
            if (remainder > 0 && allocations.get(pool) < demands.get(pool)) {
                allocations.put(pool, allocations.get(pool) + 1);
                remainder--;
            }
        }
    }

    private void ensureMinimumPoolAllocation(List<Pool> members, Map<Pool, Integer> allocations) {
        for (Pool pool : members) {
            if (allocations.get(pool) == 0) {
                Pool donor = members.stream()
                        .filter(candidate -> allocations.get(candidate) > 1)
                        .max(Comparator.<Pool>comparingInt(allocations::get)
                                .thenComparing(candidate -> candidate.registration.poolId, Comparator.reverseOrder()))
                        .orElseThrow(() -> new IllegalStateException("Unable to assign one connection to each pool"));
                allocations.put(donor, allocations.get(donor) - 1);
                allocations.put(pool, 1);
            }
        }
    }

    private Budget findBudget(String jdbcUrl) {
        Budget match = null;
        for (Budget budget : budgets) {
            if (budget.urlPattern.matcher(jdbcUrl == null ? "" : jdbcUrl).matches()) {
                if (match != null) {
                    throw new IllegalStateException("JDBC URL matches multiple database budgets: "
                            + match.name + " and " + budget.name);
                }
                match = budget;
            }
        }
        return match;
    }

    private static List<Budget> parseBudgets(Properties properties) {
        Map<String, Map<String, String>> valuesByName = groupBudgetProperties(properties);
        List<Budget> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : valuesByName.entrySet()) {
            result.add(parseBudget(entry.getKey(), entry.getValue()));
        }
        result.sort(Comparator.comparing(budget -> budget.name));
        return result;
    }

    private static Map<String, Map<String, String>> groupBudgetProperties(Properties properties) {
        Map<String, Map<String, String>> valuesByName = new HashMap<>();
        for (String property : properties.stringPropertyNames()) {
            if (!property.startsWith(PROPERTY_PREFIX)) {
                continue;
            }
            String remaining = property.substring(PROPERTY_PREFIX.length());
            int separator = remaining.indexOf('.');
            if (separator > 0) {
                valuesByName.computeIfAbsent(remaining.substring(0, separator), key -> new HashMap<>())
                        .put(remaining.substring(separator), properties.getProperty(property));
            }
        }
        return valuesByName;
    }

    private static Budget parseBudget(String name, Map<String, String> values) {
        String patternText = values.get(JDBC_URL_PATTERN_SUFFIX);
        String maxText = values.get(MAX_CONNECTIONS_SUFFIX);
        if (patternText == null || patternText.isBlank() || maxText == null) {
            throw new IllegalArgumentException("Database budget '" + name
                    + "' requires match.jdbcUrlPattern and maxTotalConnections");
        }

        int maxConnections = parsePositiveInt(name, MAX_CONNECTIONS_SUFFIX, maxText);
        int reserve = parseReserve(name, values, maxConnections);
        Map<String, Double> weights = parseUsernameWeights(name, values);
        return new Budget(name, globPattern(patternText), maxConnections, reserve, weights);
    }

    private static int parseReserve(String name, Map<String, String> values, int maxConnections) {
        String reserveValue = values.get(RESERVE_CONNECTIONS_SUFFIX);
        int reserve = reserveValue == null ? 0
                : parseNonNegativeInt(name, RESERVE_CONNECTIONS_SUFFIX, reserveValue);
        if (reserve >= maxConnections) {
            throw new IllegalArgumentException("Database budget '" + name
                    + "' reserveConnections must be less than maxTotalConnections");
        }
        return reserve;
    }

    private static Map<String, Double> parseUsernameWeights(String name, Map<String, String> values) {
        Map<String, Double> weights = new HashMap<>();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (isUsernameWeight(value.getKey())) {
                String username = extractUsername(value.getKey());
                double weight = parseWeight(name, value.getKey(), value.getValue());
                weights.put(username, weight);
            }
        }
        return weights;
    }

    private static boolean isUsernameWeight(String propertyName) {
        return propertyName.startsWith(USER_PRIORITY_SUFFIX) && propertyName.endsWith(WEIGHT_SUFFIX);
    }

    private static String extractUsername(String propertyName) {
        String username = propertyName.substring(USER_PRIORITY_SUFFIX.length(),
                propertyName.length() - WEIGHT_SUFFIX.length());
        if (username.isEmpty()) {
            throw new IllegalArgumentException("Database budget has an empty username priority");
        }
        return username;
    }

    private static double parseWeight(String budgetName, String propertyName, String value) {
        try {
            double weight = Double.parseDouble(value);
            if (!Double.isFinite(weight) || weight <= 0) {
                throw new NumberFormatException("weight must be finite and positive");
            }
            return weight;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid username weight for database budget '"
                    + budgetName + "': " + propertyName, e);
        }
    }

    private static Pattern globPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int segmentStart = 0;
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) == '*') {
                regex.append(Pattern.quote(glob.substring(segmentStart, i))).append(".*");
                segmentStart = i + 1;
            }
        }
        regex.append(Pattern.quote(glob.substring(segmentStart))).append("$");
        return Pattern.compile(regex.toString());
    }

    private static int parsePositiveInt(String name, String key, String value) {
        int parsed = parseNonNegativeInt(name, key, value);
        if (parsed == 0) {
            throw new IllegalArgumentException("Database budget '" + name + "' " + key + " must be positive");
        }
        return parsed;
    }

    private static int parseNonNegativeInt(String name, String key, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("value must not be negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for database budget '" + name + "' " + key, e);
        }
    }

    public final class Registration {
        private final String poolId;
        private final Budget budget;
        private final int requestedMax;
        private final int requestedMin;

        private Registration(String poolId, Budget budget, int requestedMax, int requestedMin) {
            this.poolId = poolId;
            this.budget = budget;
            this.requestedMax = requestedMax;
            this.requestedMin = requestedMin;
        }

        public int getMaximumPoolSize() {
            return budget == null ? requestedMax : currentMax();
        }

        private int currentMax() {
            synchronized (DatabaseConnectionBudgetManager.this) {
                Pool pool = pools.get(poolId);
                return pool == null ? requestedMax : pool.allocatedMax;
            }
        }

        public int getMinimumIdle() {
            return Math.min(requestedMin, getMaximumPoolSize());
        }
    }

    @FunctionalInterface
    public interface PoolResizer {
        void resize(int maximumPoolSize, int minimumIdle) throws SQLException;
    }

    @FunctionalInterface
    private interface InputStreamSupplier {
        InputStream get() throws IOException;
    }

    private static final class Budget {
        private final String name;
        private final Pattern urlPattern;
        private final int maxConnections;
        private final int reserveConnections;
        private final Map<String, Double> usernameWeights;

        private Budget(String name, Pattern urlPattern, int maxConnections,
                       int reserveConnections, Map<String, Double> usernameWeights) {
            this.name = name;
            this.urlPattern = urlPattern;
            this.maxConnections = maxConnections;
            this.reserveConnections = reserveConnections;
            this.usernameWeights = Map.copyOf(usernameWeights);
        }
    }

    private static final class Pool {
        private final Registration registration;
        private final String username;
        private final boolean resizable;
        private int allocatedMax;
        private int allocatedMin;
        private PoolResizer resizer;

        private Pool(Registration registration, String username, boolean resizable) {
            this.registration = registration;
            this.username = username;
            this.resizable = resizable;
            this.allocatedMax = registration.requestedMax;
            this.allocatedMin = registration.requestedMin;
        }
    }
}
