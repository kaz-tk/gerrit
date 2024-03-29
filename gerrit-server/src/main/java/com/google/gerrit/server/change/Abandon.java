// Copyright (C) 2012 The Android Open Source Project
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

import com.google.common.base.Strings;
import com.google.gerrit.common.ChangeHooks;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.DefaultInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ApprovalsUtil;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.Abandon.Input;
import com.google.gerrit.server.index.ChangeIndexer;
import com.google.gerrit.server.mail.AbandonedSender;
import com.google.gerrit.server.mail.ReplyToChangeSender;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gwtorm.server.AtomicUpdate;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

public class Abandon implements RestModifyView<ChangeResource, Input>,
    UiAction<ChangeResource> {
  private static final Logger log = LoggerFactory.getLogger(Abandon.class);

  private final ChangeHooks hooks;
  private final AbandonedSender.Factory abandonedSenderFactory;
  private final Provider<ReviewDb> dbProvider;
  private final ChangeJson json;
  private final ChangeIndexer indexer;

  public static class Input {
    @DefaultInput
    public String message;
  }

  @Inject
  Abandon(ChangeHooks hooks,
      AbandonedSender.Factory abandonedSenderFactory,
      Provider<ReviewDb> dbProvider,
      ChangeJson json,
      ChangeIndexer indexer) {
    this.hooks = hooks;
    this.abandonedSenderFactory = abandonedSenderFactory;
    this.dbProvider = dbProvider;
    this.json = json;
    this.indexer = indexer;
  }

  @Override
  public Object apply(ChangeResource req, Input input)
      throws BadRequestException, AuthException,
      ResourceConflictException, Exception {
    ChangeControl control = req.getControl();
    IdentifiedUser caller = (IdentifiedUser) control.getCurrentUser();
    Change change = req.getChange();
    if (!control.canAbandon()) {
      throw new AuthException("abandon not permitted");
    } else if (!change.getStatus().isOpen()) {
      throw new ResourceConflictException("change is " + status(change));
    }

    ChangeMessage message;
    ReviewDb db = dbProvider.get();
    db.changes().beginTransaction(change.getId());
    try {
      change = db.changes().atomicUpdate(
        change.getId(),
        new AtomicUpdate<Change>() {
          @Override
          public Change update(Change change) {
            if (change.getStatus().isOpen()) {
              change.setStatus(Change.Status.ABANDONED);
              ChangeUtil.updated(change);
              return change;
            }
            return null;
          }
        });
      if (change == null) {
        throw new ResourceConflictException("change is "
            + status(db.changes().get(req.getChange().getId())));
      }
      message = newMessage(input, caller, change);
      db.changeMessages().insert(Collections.singleton(message));
      new ApprovalsUtil(db).syncChangeStatus(change);
      db.commit();
    } finally {
      db.rollback();
    }

    indexer.index(change);
    try {
      ReplyToChangeSender cm = abandonedSenderFactory.create(change);
      cm.setFrom(caller.getAccountId());
      cm.setChangeMessage(message);
      cm.send();
    } catch (Exception e) {
      log.error("Cannot email update for change " + change.getChangeId(), e);
    }
    hooks.doChangeAbandonedHook(change,
        caller.getAccount(),
        db.patchSets().get(change.currentPatchSetId()),
        Strings.emptyToNull(input.message),
        db);
    return json.format(change);
  }

  @Override
  public UiAction.Description getDescription(ChangeResource resource) {
    return new UiAction.Description()
      .setLabel("Abandon")
      .setTitle("Abandon the change")
      .setVisible(resource.getChange().getStatus().isOpen()
          && resource.getControl().canAbandon());
  }

  private ChangeMessage newMessage(Input input, IdentifiedUser caller,
      Change change) throws OrmException {
    StringBuilder msg = new StringBuilder();
    msg.append("Abandoned");
    if (!Strings.nullToEmpty(input.message).trim().isEmpty()) {
      msg.append("\n\n");
      msg.append(input.message.trim());
    }

    ChangeMessage message = new ChangeMessage(
        new ChangeMessage.Key(
            change.getId(),
            ChangeUtil.messageUUID(dbProvider.get())),
        caller.getAccountId(),
        change.getLastUpdatedOn(),
        change.currentPatchSetId());
    message.setMessage(msg.toString());
    return message;
  }

  private static String status(Change change) {
    return change != null ? change.getStatus().name().toLowerCase() : "deleted";
  }
}
