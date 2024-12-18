package org.cheban.swisstoolbot.service;

import org.cheban.swisstoolbot.objects.LocationInfo;

public interface GeocodeService {
  LocationInfo coordinates(String query);

  String location(Double lat, Double lng);
}
