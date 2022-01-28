package com.flightaware.aeroapps;

//CHECKSTYLE.OFF: AvoidStarImport
import static spark.Spark.*;
//CHECKSTYLE.ON: AvoidStarImport

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

    static String AEROAPI_BASE_URL = "https://aeroapi.flightaware.com/aeroapi";
    static String AEROAPI_KEY = System.getenv("AEROAPI_KEY");
    static final OkHttpClient client = new OkHttpClient();

    static ObjectMapper mapper = new ObjectMapper();

    // Cache of API resources to prevent excessive AeroAPI queries on page refresh
    // A cache get for a missing entry will return an empty object instance
    static LoadingCache<String, ArrayNode> CACHE = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build(key -> mapper.createArrayNode());

    static LoadingCache<String, ObjectNode> FLIGHT_CACHE = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build(key -> mapper.createObjectNode());

    static final Logger logger = LoggerFactory.getLogger("App");

    // SparkJava configuration and routes //

    /**
    * The configuration and routes offered by the backend.
     */
    public static void main(String[] args) {
        // Change from default port 4567
        port(5000);

        // Sets default response type header for all routes to json
        before((req, res) -> res.type("application/json"));

        // Change default HTML 404 response to JSON
        notFound((req, res) -> "{\"error\":\"Not Found\"}");

        // Actual fids endpoints
        get("/positions/:faFlightId", (req, res) -> get_positions(req.params("faFlightId")));
        get("/flights/", (req, res) -> get_flight_random());
        get("/flights/:faFlightId", (req, res) -> get_flight(req.params("faFlightId")));
        get("/airports/", (req, res) -> get_busiest_airports());
        get("/airports/:airport/arrivals", (req, res) -> airport_arrivals(req.params("airport")));
        get("/airports/:airport/departures", (req, res) ->
            airport_departures(req.params("airport"))
        );
        get("/airports/:airport/enroute", (req, res) -> airport_enroute(req.params("airport")));
        get("/airports/:airport/scheduled", (req, res) -> airport_scheduled(req.params("airport")));
        get("/map/:faFlightId", (req, res) -> map(req.params("faFlightId")));
    }


    // Internal support functions //
    /**
    * Makes an AeroAPI request.
    *
    * @param resource an AeroAPI resource URI
    * @return         An AeroAPI response body
    */
    private static JsonNode aeroapi_get(String resource) {
        int code = 500;
        ObjectNode result = mapper.createObjectNode();

        Request request = new Request.Builder()
            .header("x-apikey", AEROAPI_KEY)
            .url(String.format("%s%s", AEROAPI_BASE_URL, resource))
            .build();

        // both execute() and readTree() can raise exceptions that must be handled
        try {
            Response response = client.newCall(request).execute();
            code = response.code();
            result = (ObjectNode) mapper.readTree(response.body().string());
        } catch (Exception e) {
            // AeroAPI will normally produce useful errors
            // In the case of a caught error emulate this style
            result.put("title", e.getClass().getSimpleName());
            result.put("detail", e.getMessage());
        }

        result.put("status", code);
        return result;
    }

    /**
    * Common tasks involved with a boards (airports) request.
    *
    * @param apiResource  The API resource URI
    * @param apiObjectKey The top level key expected api response object
    * @return             The processed flights object
     */
    private static String boards_request(String apiResource, String apiObjectKey) {
        ArrayNode flights = mapper.createArrayNode();
        ArrayNode idList = CACHE.get(apiResource);

        if (idList.size() > 0) {
            idList.forEach(faFlightId -> {
                ObjectNode entry = FLIGHT_CACHE.get(faFlightId.asText());
                if (entry.size() > 0) {
                    flights.add(entry);
                }
            });
        }

        if (flights.size() >= 15) {
            logger.info("Populating {} from cache", apiResource);
        } else {
            logger.info("Making AeroAPI request to GET {}", apiResource);
            JsonNode response = aeroapi_get(apiResource);

            if (response.get("status").asInt() != 200) {
                logger.error(response.toString());
                halt(response.get("status").asInt(), response.toString());
            }

            JsonNode payload = format_response(response, apiObjectKey);
            payload.forEach(entry -> {
                ObjectNode node = (ObjectNode) entry;
                FLIGHT_CACHE.put(node.get("id").asText(), node);
                flights.add(node);
                idList.add(node.get("id").asText());
            });
            CACHE.put(apiResource, idList);
        }

        return flights.toString();
    }

    /**
    * Formats an AeroAPI boards or flights/{id} response to maintain
    * parity with Firestarter and work in common fids_frontend.
    *
    * @param rawPayload A raw AeroAPI involved as a JsonNode object
    * @param topLevel   The top level key in the JsonNode object
    * @return           A formatted JSON response body
    */
    private static JsonNode format_response(JsonNode rawPayload, String topLevel) {

        //CHECKSTYLE.OFF: VariableDeclarationUsageDistance
        List<String> missing = Arrays.asList(
            "actual_runway_off",
            "actual_runway_on",
            "cruising_altitude",
            "filed_ground_speed",
            "hexid",
            "predicted_in",
            "predicted_off",
            "predicted_on",
            "predicted_out",
            "status",
            "true_cancel"
        );
        List<String> origDest = Arrays.asList("destination", "origin");
        Map<String, String> rename = new HashMap<String, String>();
        rename.put("ident", "flight_number");
        rename.put("filed_airspeed", "filed_speed");
        rename.put("faFlightId", "id");
        rename.put("gate_origin", "actual_departure_gate");
        rename.put("gate_destination", "actual_arrival_gate");
        rename.put("terminal_origin", "actual_departure_terminal");
        rename.put("terminal_destination", "actual_arrival_terminal");
        //CHECKSTYLE.ON: VariableDeclarationUsageDistance

        ArrayNode formatted = mapper.createArrayNode();

        rawPayload.get(topLevel).forEach(entry -> {

            // cast to ObjectNode for easier manipulation
            ObjectNode node = (ObjectNode) entry;

            // pad out missing keys to keep data structure in parity with firestarter
            missing.forEach(key -> node.putNull(key));

            // flatten orig/dest object to a key:value
            origDest.forEach(key -> 
                node.put(key, node.get(key).get("code"))
            );

            // rename keys for parity with firestarter
            rename.forEach((key, value) -> {
                node.put(value, node.get(key));
                node.remove(key);
            });

            formatted.add(node);
        });

        // flights should return just the object, not a list containing one object
        if (topLevel == "flights") {
            return formatted.get(0);
        }

        return formatted;
    }


    // Endpoint specific functions //
    /**
    * Gets the positions of a given flight.
    *
    * @param faFlightId the FlightAware Flight ID to look up
    * @return           JSON flight information in string representation
    */
    private static String get_positions(String faFlightId) {
        String apiResource = String.format("/flights/%s/track", faFlightId);
        ArrayNode postions = CACHE.get(apiResource);

        if (postions.size() > 0) {
            logger.info("Populating {} from cache", apiResource);
        } else {
            logger.info("Making AeroAPI request to GET {}", apiResource);
            JsonNode response = aeroapi_get(apiResource);

            if (response.get("status").asInt() != 200) {
                logger.error(response.toString());
                halt(response.get("status").asInt(), response.toString());
            }

            postions = (ArrayNode) response.get("positions");
            CACHE.put(apiResource, postions);
        }

        return postions.toString();
    }

    /**
    * Gets the details of a given flight.
    *
    * @param faFlightId The FlightAware Flight ID to look up
    * @return           JSON flight information in string representation
    */
    private static String get_flight(String faFlightId) {
        String apiResource = String.format("/flights/%s", faFlightId);
        ObjectNode flight = FLIGHT_CACHE.get(faFlightId);

        if (flight.size() > 0) {
            logger.info("Populating {} from cache", apiResource);
        } else {
            logger.info("Making AeroAPI request to GET {}", apiResource);
            JsonNode response = aeroapi_get(apiResource);

            if (response.get("status").asInt() != 200) {
                logger.error(response.toString());
                halt(response.get("status").asInt(), response.toString());
            }

            flight = (ObjectNode) format_response(response, "flights");
            FLIGHT_CACHE.put(faFlightId, flight);
        }

        return flight.toString();
    }

    /**
    * Gets the details of a random flight.
    *
    * @return JSON flight information in string representation
    */
    private static String get_flight_random() {
        String apiResource = "/flights/search?query=-inAir 1";
        ArrayNode flights = CACHE.get(apiResource);
        Random rand = new Random();
        
        if (flights.size() > 0) {
            logger.info("Populating {} from cache", apiResource);
        } else {
            logger.info("Making AeroAPI request to GET {}", apiResource);
            JsonNode response = aeroapi_get(apiResource);

            if (response.get("status").asInt() != 200) {
                logger.error(response.toString());
                halt(response.get("status").asInt(), response.toString());
            }

            response.get("flights").forEach(entry -> 
                flights.add(entry.get("faFlightId").asText())
            );
            CACHE.put(apiResource, flights);
        }

        return get_flight(flights.get(rand.nextInt(flights.size())).asText());
    }


    /**
    * Gets the busiest airports by cancellation volume.
    *
    * @return JSON array of airport codes in string representation
    */
    private static String get_busiest_airports() {
        String apiResource = "/disruption_counts/origin";
        ArrayNode airports = CACHE.get(apiResource);

        if (airports.size() > 0) {
            logger.info("Populating {} from cache", apiResource);
        } else {
            logger.info("Making AeroAPI request to GET {}", apiResource);
            JsonNode response = aeroapi_get(apiResource);

            if (response.get("status").asInt() != 200) {
                logger.error(response.toString());
                halt(response.get("status").asInt(), response.toString());
            }

            response.get("entities").forEach(entry -> 
                airports.add(entry.get("entity_id").asText())
            );
            CACHE.put(apiResource, airports);
        }

        return airports.toString();
    }

    /**
    * Get a list of arrivals for a certain airport.
    *
    * @param  airport The airport code to fetch arrivals for
    * @return         JSON array of airport codes in string representation
    */
    private static String airport_arrivals(String airport) {
        String apiResource = String.format("/airports/%s/flights/arrivals", airport);
        return boards_request(apiResource, "arrivals");
    }

    /**
    * Get a list of departures for a certain airport.
    *
    * @param  airport The airport code to fetch departures for
    * @return         JSON array of airport codes in string representation
    */
    private static String airport_departures(String airport) {
        String apiResource = String.format("/airports/%s/flights/departures", airport);
        return boards_request(apiResource, "departures");
    }

    /**
    * "Get a list of flights enroute to a certain airport.
    *
    * @param  airport The airport code to fetch enroute for
    * @return         JSON array of airport codes in string representation
    */
    private static String airport_enroute(String airport) {
        String apiResource = String.format("/airports/%s/flights/scheduled_arrivals", airport);
        return boards_request(apiResource, "scheduled_arrivals");
    }

    /**
    * Get a list of scheduled flights from a certain airport.
    *
    * @param  airport The airport code to fetch scheduled for
    * @return         JSON array of airport codes in string representation
    */
    private static String airport_scheduled(String airport) {
        String apiResource = String.format("/airports/%s/flights/scheduled_departures", airport);
        return boards_request(apiResource, "scheduled_departures");
    }

    /**
    * Get a static map image of the current flight.
    *
    * @param  faFlightId The flight code to fetch a map image for
    * @return            Base64 representation of a png map tile
    */
    private static String map(String faFlightId) {
        String apiResource = String.format("/flights/%s/map", faFlightId);
        ArrayNode map = CACHE.get(apiResource);

        if (map.size() > 0) {
            logger.info("Populating {} from cache", apiResource);
        } else {
            logger.info("Making AeroAPI request to GET {}", apiResource);
            JsonNode response = aeroapi_get(apiResource);

            if (response.get("status").asInt() != 200) {
                logger.error(response.toString());
                halt(response.get("status").asInt(), response.toString());
            }

            map.add(response.get("map"));
            CACHE.put(apiResource, map);
        }

        return map.toString();
    }
}
