package org.openjproxy.grpc.server.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

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

    private static final DatabaseConnectionBudgetManager INSTANCE =
            new DatabaseConnectionBudgetManager(System.getProperties());

    private final List<Budget> budgets;
    private final Map<String, Pool> pools = new HashMap<>();

    public DatabaseConnectionBudgetManager(Properties properties) {
        this.budgets = parseBudgets(properties);
    }

    public static DatabaseConnectionBudgetManager getInstance() {
        return INSTANCE;
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

    public synchronized void attachPool(Registration registration, PoolResizer resizer) {
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
        resize(pool, pool.allocatedMax, pool.allocatedMin);
    }

    public synchronized void unregisterPool(Registration registration) {
        if (registration == null || registration.budget == null) {
            return;
        }
        Pool removed = pools.remove(registration.poolId);
        if (removed != null) {
            rebalance(removed.registration.budget);
        }
    }

    private void rebalance(Budget budget) {
        List<Pool> members = new ArrayList<>();
        for (Pool pool : pools.values()) {
            if (pool.registration.budget == budget) {
                members.add(pool);
            }
        }
        members.sort(Comparator.comparing(pool -> pool.registration.poolId));
        if (members.isEmpty()) {
            return;
        }

        int capacity = budget.maxConnections - budget.reserveConnections;
        if (capacity < members.size()) {
            throw new IllegalStateException("Database budget '" + budget.name + "' has " + capacity
                    + " usable connections for " + members.size() + " pools; each pool requires at least one");
        }

        Map<Pool, Integer> allocations = allocate(members, capacity, budget);
        for (Pool pool : members) {
            int target = allocations.get(pool);
            if (target != pool.allocatedMax && !pool.resizable) {
                throw new IllegalStateException("Pool '" + pool.registration.poolId
                        + "' cannot be resized to enforce database budget '" + budget.name + "'");
            }
        }

        for (Pool pool : members) {
            int target = allocations.get(pool);
            if (target < pool.allocatedMax && pool.resizer != null) {
                resize(pool, target, Math.min(pool.registration.requestedMin, target));
            }
        }
        for (Pool pool : members) {
            int target = allocations.get(pool);
            if (target > pool.allocatedMax && pool.resizer != null) {
                resize(pool, target, Math.min(pool.registration.requestedMin, target));
            }
        }
        for (Pool pool : members) {
            pool.allocatedMax = allocations.get(pool);
            pool.allocatedMin = Math.min(pool.registration.requestedMin, pool.allocatedMax);
        }
    }

    private void resize(Pool pool, int max, int min) {
        if (pool.resizer == null) {
            return;
        }
        try {
            pool.resizer.resize(max, min);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resize pool '" + pool.registration.poolId
                    + "' to max=" + max + ", min=" + min, e);
        }
    }

    private Map<Pool, Integer> allocate(List<Pool> members, int capacity, Budget budget) {
        Map<Pool, Integer> allocations = new LinkedHashMap<>();
        long totalRequested = 0;
        for (Pool pool : members) {
            totalRequested += pool.registration.requestedMax;
            allocations.put(pool, 1);
        }
        if (totalRequested <= capacity) {
            for (Pool pool : members) {
                allocations.put(pool, pool.registration.requestedMax);
            }
            return allocations;
        }

        int remaining = capacity - members.size();
        Map<Pool, Integer> demands = new HashMap<>();
        Map<String, Integer> poolsByUsername = new HashMap<>();
        for (Pool pool : members) {
            poolsByUsername.merge(pool.username, 1, Integer::sum);
            demands.put(pool, pool.registration.requestedMax - 1);
        }

        List<Pool> eligible = new ArrayList<>(members);
        while (remaining > 0 && !eligible.isEmpty()) {
            double totalWeight = 0;
            Map<Pool, Double> weights = new HashMap<>();
            for (Pool pool : eligible) {
                double weight = budget.usernameWeights.getOrDefault(pool.username, 1.0)
                        / poolsByUsername.get(pool.username);
                weights.put(pool, weight);
                totalWeight += weight;
            }

            double allocationLevel = remaining / totalWeight;
            Pool saturated = null;
            for (Pool pool : eligible) {
                if (demands.get(pool) <= allocationLevel * weights.get(pool)) {
                    saturated = pool;
                    break;
                }
            }
            if (saturated != null) {
                int demand = demands.remove(saturated);
                allocations.put(saturated, allocations.get(saturated) + demand);
                remaining -= demand;
                eligible.remove(saturated);
                continue;
            }

            Map<Pool, Double> fractional = new HashMap<>();
            int assigned = 0;
            for (Pool pool : eligible) {
                double share = allocationLevel * weights.get(pool);
                int whole = (int) Math.floor(share);
                allocations.put(pool, allocations.get(pool) + whole);
                fractional.put(pool, share - whole);
                assigned += whole;
            }
            remaining -= assigned;
            eligible.sort(Comparator.<Pool>comparingDouble(fractional::get).reversed()
                    .thenComparing(pool -> pool.registration.poolId));
            for (Pool pool : eligible) {
                if (remaining == 0) {
                    break;
                }
                if (allocations.get(pool) < pool.registration.requestedMax) {
                    allocations.put(pool, allocations.get(pool) + 1);
                    remaining--;
                }
            }
            break;
        }
        return allocations;
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

        List<Budget> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : valuesByName.entrySet()) {
            String name = entry.getKey();
            Map<String, String> values = entry.getValue();
            String patternText = values.get(JDBC_URL_PATTERN_SUFFIX);
            String maxText = values.get(MAX_CONNECTIONS_SUFFIX);
            if (patternText == null || patternText.isBlank() || maxText == null) {
                throw new IllegalArgumentException("Database budget '" + name
                        + "' requires match.jdbcUrlPattern and maxTotalConnections");
            }

            int maxConnections = parsePositiveInt(name, MAX_CONNECTIONS_SUFFIX, maxText);
            int reserve = values.containsKey(RESERVE_CONNECTIONS_SUFFIX)
                    ? parseNonNegativeInt(name, RESERVE_CONNECTIONS_SUFFIX, values.get(RESERVE_CONNECTIONS_SUFFIX))
                    : 0;
            if (reserve >= maxConnections) {
                throw new IllegalArgumentException("Database budget '" + name
                        + "' reserveConnections must be less than maxTotalConnections");
            }

            Map<String, Double> weights = new HashMap<>();
            for (Map.Entry<String, String> value : values.entrySet()) {
                if (value.getKey().startsWith(USER_PRIORITY_SUFFIX) && value.getKey().endsWith(WEIGHT_SUFFIX)) {
                    String username = value.getKey().substring(USER_PRIORITY_SUFFIX.length(),
                            value.getKey().length() - WEIGHT_SUFFIX.length());
                    if (username.isEmpty()) {
                        throw new IllegalArgumentException("Database budget '" + name + "' has an empty username priority");
                    }
                    try {
                        double weight = Double.parseDouble(value.getValue());
                        if (!Double.isFinite(weight) || weight <= 0) {
                            throw new NumberFormatException("weight must be finite and positive");
                        }
                        weights.put(username, weight);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid username weight for database budget '"
                                + name + "': " + value.getKey(), e);
                    }
                }
            }

            result.add(new Budget(name, globPattern(patternText), maxConnections, reserve, weights));
        }
        result.sort(Comparator.comparing(budget -> budget.name));
        return result;
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
        void resize(int maximumPoolSize, int minimumIdle) throws Exception;
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
