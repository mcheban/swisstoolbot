package org.cheban.swisstoolbot.service;

import org.cheban.swisstoolbot.objects.ImageResult;
import org.cheban.swisstoolbot.objects.WebResult;

import java.util.List;

public interface WebSearchService {
  List<WebResult> search(String query, int num);

  List<ImageResult> searchImages(String query, int num);
}
