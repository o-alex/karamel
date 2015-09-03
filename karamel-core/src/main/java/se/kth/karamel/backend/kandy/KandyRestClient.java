/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package se.kth.karamel.backend.kandy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import se.kth.karamel.backend.stats.ClusterStats;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import javax.ws.rs.core.Response;
import se.kth.karamel.common.Settings;
import se.kth.karamel.common.exception.KaramelException;

/**
 *
 * @author kamal
 */
public class KandyRestClient {

  private static ClientConfig config;
  private static Client client;
  private static WebResource service;

  private static void checkResources() {
    config = new DefaultClientConfig();
    client = Client.create(config);
    service = client.resource(UriBuilder.fromUri(Settings.KANDY_REST_STATS_STORE).build());
  }

  public static void pushClusterStats(ClusterStats stats) throws KaramelException {
    checkResources();
    GsonBuilder builder = new GsonBuilder();
    builder.disableHtmlEscaping();
    Gson gson = builder.setPrettyPrinting().create();
    String json = gson.toJson(stats);

    ClientResponse response = service.type(MediaType.TEXT_PLAIN).post(ClientResponse.class, json);
    if (response.getStatus() >= 300) {
      throw new KaramelException(String.format("Kandy server couldn't store the cluster stats because '%s'",
          response.getStatusInfo().getReasonPhrase()));
    }
  }

}
