// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import org.eclipse.jgit.lib.Config;

import java.util.ArrayList;

class ConfigAnnotationParser {

  private static Splitter splitter = Splitter.on(".").trimResults();

  static Config parse(GerritConfigs annotation) {
    if (annotation == null) {
      return null;
    }

    Config cfg = new Config();
    for (GerritConfig c : annotation.value()) {
      parse(cfg, c);
    }
    return cfg;
  }

  static Config parse(GerritConfig annotation) {
    Config cfg = new Config();
    parse(cfg, annotation);
    return cfg;
  }

  static private void parse(Config cfg, GerritConfig c) {
    ArrayList<String> l = Lists.newArrayList(splitter.split(c.name()));
    if (l.size() == 2) {
      cfg.setString(l.get(0), null, l.get(1), c.value());
    } else if (l.size() == 3) {
      cfg.setString(l.get(0), l.get(1), l.get(2), c.value());
    } else {
      throw new IllegalArgumentException(
          "GerritConfig.name must be of the format"
              + " section.subsection.name or section.name");
    }
  }
}
