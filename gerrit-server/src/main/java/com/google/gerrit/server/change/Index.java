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
// limitations under the License.package com.google.gerrit.server.git;

package com.google.gerrit.server.change;

import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.Index.Input;
import com.google.gerrit.server.index.ChangeIndexer;
import com.google.inject.Inject;

import java.util.concurrent.ExecutionException;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
public class Index implements RestModifyView<ChangeResource, Input> {
  public static class Input {
  }

  private final ChangeIndexer indexer;

  @Inject
  Index(ChangeIndexer indexer) {
    this.indexer = indexer;
  }

  @Override
  public Object apply(ChangeResource rsrc, Input input)
      throws InterruptedException, ExecutionException {
    indexer.index(rsrc.getChange()).get();
    return Response.none();
  }
}
