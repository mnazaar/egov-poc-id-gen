package org.egov.idgen.config;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.egov.idgen.api.ErrorCode;
import org.egov.idgen.api.IdgenException;

/** Tenants loaded from cities.csv. A city is named by tenant ID or city code, case-insensitively. */
@ApplicationScoped
public class CityRegistry {

    private volatile List<City> cities = List.of();
    private volatile Map<String, City> byName = Map.of();

    /** Replaces the registry contents. Called once at startup by {@link DataLoader}. */
    public void load(List<City> loaded) {
        Map<String, City> index = new HashMap<>();
        for (City c : loaded) {
            index.put(norm(c.tenantId()), c);
            index.put(norm(c.code()), c);
        }
        this.byName = Map.copyOf(index);
        this.cities = List.copyOf(loaded);
    }

    /** @throws IdgenException UNKNOWN_CITY when the name matches no tenant ID or city code */
    public City resolve(String city) {
        City c = city == null ? null : byName.get(norm(city));
        if (c == null) {
            throw new IdgenException(ErrorCode.UNKNOWN_CITY,
                    "City '" + city + "' matches no tenant ID or city code",
                    Map.of("city", String.valueOf(city)));
        }
        return c;
    }

    public List<City> all() {
        return cities;
    }

    public static CityRegistry of(List<City> cities) {
        CityRegistry r = new CityRegistry();
        r.load(cities);
        return r;
    }

    static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
