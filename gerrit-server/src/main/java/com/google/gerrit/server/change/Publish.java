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

package com.google.gerrit.server.change;

import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.mail.PatchSetNotificationSender;
import com.google.gerrit.server.change.Publish.Input;
import com.google.gerrit.server.index.ChangeIndexer;
import com.google.gerrit.server.patch.PatchSetInfoNotAvailableException;
import com.google.gwtorm.server.AtomicUpdate;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;

import java.io.IOException;

public class Publish implements RestModifyView<RevisionResource, Input>,
    UiAction<RevisionResource> {
  public static class Input {
  }

  private final Provider<ReviewDb> dbProvider;
  private final PatchSetNotificationSender sender;
  private final ChangeHooks hooks;
  private final ChangeIndexer indexer;

  @Inject
  public Publish(Provider<ReviewDb> dbProvider,
      PatchSetNotificationSender sender,
      ChangeHooks hooks,
      ChangeIndexer indexer) {
    this.dbProvider = dbProvider;
    this.sender = sender;
    this.hooks = hooks;
    this.indexer = indexer;
  }

  @Override
  public Object apply(RevisionResource rsrc, Input input) throws IOException,
      ResourceNotFoundException, ResourceConflictException,
      OrmException, AuthException {
    if (!rsrc.getPatchSet().isDraft()) {
      throw new ResourceConflictException("Patch set is not a draft");
    }

    if (!rsrc.getControl().canPublish(dbProvider.get())) {
      throw new AuthException("Cannot publish this draft patch set");
    }

    PatchSet updatedPatchSet = updateDraftPatchSet(rsrc);
    Change updatedChange = updateDraftChange(rsrc);

    try {
      if (!updatedPatchSet.isDraft()
          || updatedChange.getStatus() == Change.Status.NEW) {
        indexer.index(updatedChange);
        hooks.doDraftPublishedHook(updatedChange, updatedPatchSet, dbProvider.get());
        sender.send(rsrc.getChange().getStatus() == Change.Status.DRAFT,
            rsrc.getUser(), updatedChange, updatedPatchSet,
            rsrc.getControl().getLabelTypes());
      }
    } catch (PatchSetInfoNotAvailableException e) {
      throw new ResourceNotFoundException(e.getMessage());
    }

    return Response.none();
  }

  private Change updateDraftChange(RevisionResource rsrc) throws OrmException {
    Change updatedChange = dbProvider.get().changes()
        .atomicUpdate(rsrc.getChange().getId(),
        new AtomicUpdate<Change>() {
      @Override
      public Change update(Change change) {
        if (change.getStatus() == Change.Status.DRAFT) {
          change.setStatus(Change.Status.NEW);
          ChangeUtil.updated(change);
        }
        return change;
      }
    });
    return updatedChange;
  }

  private PatchSet updateDraftPatchSet(RevisionResource rsrc) throws OrmException {
    final PatchSet updatedPatchSet = dbProvider.get().patchSets()
        .atomicUpdate(rsrc.getPatchSet().getId(),
        new AtomicUpdate<PatchSet>() {
      @Override
      public PatchSet update(PatchSet patchset) {
        patchset.setDraft(false);
        return patchset;
      }
    });
    return updatedPatchSet;
  }

  @Override
  public UiAction.Description getDescription(RevisionResource rsrc) {
    PatchSet.Id current = rsrc.getChange().currentPatchSetId();
    try {
      return new UiAction.Description()
        .setTitle(String.format("Publish revision %d",
            rsrc.getPatchSet().getPatchSetId()))
        .setVisible(rsrc.getPatchSet().isDraft()
            && rsrc.getPatchSet().getId().equals(current)
            && rsrc.getControl().canPublish(dbProvider.get()));
    } catch (OrmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static class CurrentRevision implements
      RestModifyView<ChangeResource, Input> {
    private final Provider<ReviewDb> dbProvider;
    private final Publish publish;

    @Inject
    CurrentRevision(Provider<ReviewDb> dbProvider,
        Publish publish) {
      this.dbProvider = dbProvider;
      this.publish = publish;
    }

    @Override
    public Object apply(ChangeResource rsrc, Input input) throws AuthException,
        ResourceConflictException, ResourceConflictException, IOException,
        OrmException, ResourceNotFoundException, AuthException {
      PatchSet ps = dbProvider.get().patchSets()
        .get(rsrc.getChange().currentPatchSetId());
      if (ps == null) {
        throw new ResourceConflictException("current revision is missing");
      } else if (!rsrc.getControl().isPatchVisible(ps, dbProvider.get())) {
        throw new AuthException("current revision not accessible");
      }
      return publish.apply(new RevisionResource(rsrc, ps), input);
    }
  }
}
