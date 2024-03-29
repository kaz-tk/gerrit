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

package com.google.gerrit.client.change;

import com.google.gerrit.client.Gerrit;
import com.google.gerrit.client.account.AccountInfo;
import com.google.gerrit.client.account.AccountInfo.AvatarInfo;
import com.google.gerrit.client.changes.ChangeApi;
import com.google.gerrit.client.changes.ChangeInfo;
import com.google.gerrit.client.changes.ChangeInfo.ApprovalInfo;
import com.google.gerrit.client.changes.ChangeInfo.LabelInfo;
import com.google.gerrit.client.changes.Util;
import com.google.gerrit.client.rpc.GerritCallback;
import com.google.gerrit.client.rpc.Natives;
import com.google.gerrit.common.PageLinks;
import com.google.gerrit.common.data.LabelValue;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.ImageResourceRenderer;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwtexpui.safehtml.client.SafeHtml;
import com.google.gwtexpui.safehtml.client.SafeHtmlBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Displays a table of label and reviewer scores. */
class Labels extends Grid {
  private static final String DATA_ID = "data-id";
  private static final String REMOVE;

  static {
    REMOVE = DOM.createUniqueId().replace('-', '_');
    init(REMOVE);
  }

  private static final native void init(String r) /*-{
    $wnd[r] = $entry(function(e) {
      @com.google.gerrit.client.change.Labels::onRemove(Lcom/google/gwt/dom/client/NativeEvent;)(e)
    });
  }-*/;

  private static void onRemove(NativeEvent event) {
    Integer user = getDataId(event);
    if (user != null) {
      final ChangeScreen2 screen = ChangeScreen2.get(event);
      ChangeApi.reviewer(screen.getChangeId().get(), user).delete(
          new GerritCallback<JavaScriptObject>() {
            @Override
            public void onSuccess(JavaScriptObject result) {
              if (screen.isCurrentView()) {
                Gerrit.display(PageLinks.toChange(screen.getChangeId()));
              }
            }
          });
    }
  }

  private static Integer getDataId(NativeEvent event) {
    Element e = event.getEventTarget().cast();
    while (e != null) {
      String v = e.getAttribute(DATA_ID);
      if (!v.isEmpty()) {
        return Integer.parseInt(v);
      }
      e = e.getParentElement();
    }
    return null;
  }

  private ChangeScreen2.Style style;
  private Element statusText;

  void init(ChangeScreen2.Style style, Element statusText) {
    this.style = style;
    this.statusText = statusText;
  }

  boolean set(ChangeInfo info, boolean current) {
    List<String> names = new ArrayList<String>(info.labels());
    Collections.sort(names);

    boolean canSubmit = info.status().isOpen();
    resize(names.size(), 2);

    for (int row = 0; row < names.size(); row++) {
      String name = names.get(row);
      LabelInfo label = info.label(name);
      setText(row, 0, name);
      if (label.all() != null) {
        setWidget(row, 1, renderUsers(label));
      }
      getCellFormatter().setStyleName(row, 0, style.labelName());
      getCellFormatter().addStyleName(row, 0, getStyleForLabel(label));

      if (canSubmit && info.status() == Change.Status.NEW) {
        switch (label.status()) {
          case NEED:
            if (current) {
              statusText.setInnerText("Needs " + name);
            }
            canSubmit = false;
            break;
          case REJECT:
          case IMPOSSIBLE:
            if (current) {
              statusText.setInnerText("Not " + name);
            }
            canSubmit = false;
            break;
          default:
            break;
          }
      }
    }
    return canSubmit;
  }

  private Widget renderUsers(LabelInfo label) {
    Map<Integer, List<ApprovalInfo>> m = new HashMap<Integer, List<ApprovalInfo>>(4);
    int approved = 0, rejected = 0;

    for (ApprovalInfo ai : Natives.asList(label.all())) {
      if (ai.value() != 0) {
        List<ApprovalInfo> l = m.get(Integer.valueOf(ai.value()));
        if (l == null) {
          l = new ArrayList<ApprovalInfo>(label.all().length());
          m.put(Integer.valueOf(ai.value()), l);
        }
        l.add(ai);

        if (isRejected(label, ai)) {
          rejected = ai.value();
        } else if (isApproved(label, ai)) {
          approved = ai.value();
        }
      }
    }

    SafeHtmlBuilder html = new SafeHtmlBuilder();
    for (Integer v : sort(m.keySet(), approved, rejected)) {
      if (!html.isEmpty()) {
        html.append("; ");
      }

      String val = LabelValue.formatValue(v.shortValue());
      html.openSpan();
      html.setAttribute("title", label.value_text(val));
      if (v.intValue() == approved) {
        html.setStyleName(style.label_ok());
      } else if (v.intValue() == rejected) {
        html.setStyleName(style.label_reject());
      }
      html.append(val).append(" ");
      html.append(formatUserList(style, m.get(v),
          Collections.<Integer> emptySet()));
      html.closeSpan();
    }
    return html.toBlockWidget();
  }

  private static List<Integer> sort(Set<Integer> keySet, int a, int b) {
    List<Integer> r = new ArrayList<Integer>(keySet);
    Collections.sort(r);
    if (keySet.contains(a)) {
      r.remove(Integer.valueOf(a));
      r.add(0, a);
    } else if (keySet.contains(b)) {
      r.remove(Integer.valueOf(b));
      r.add(0, b);
    }
    return r;
  }

  private static boolean isApproved(LabelInfo label, ApprovalInfo ai) {
    return label.approved() != null
        && label.approved()._account_id() == ai._account_id();
  }

  private static boolean isRejected(LabelInfo label, ApprovalInfo ai) {
    return label.rejected() != null
        && label.rejected()._account_id() == ai._account_id();
  }

  private String getStyleForLabel(LabelInfo label) {
    switch (label.status()) {
      case OK:
        return style.label_ok();
      case NEED:
        return style.label_need();
      case REJECT:
      case IMPOSSIBLE:
        return style.label_reject();
      default:
      case MAY:
        return style.label_may();
    }
  }

  static SafeHtml formatUserList(ChangeScreen2.Style style,
      Collection<? extends AccountInfo> in,
      Set<Integer> removable) {
    List<AccountInfo> users = new ArrayList<AccountInfo>(in);
    Collections.sort(users, new Comparator<AccountInfo>() {
      @Override
      public int compare(AccountInfo a, AccountInfo b) {
        String as = name(a);
        String bs = name(b);
        if (as.isEmpty()) {
          return 1;
        } else if (bs.isEmpty()) {
          return -1;
        }
        return as.compareTo(bs);
      }

      private String name(AccountInfo a) {
        if (a.name() != null) {
          return a.name();
        } else if (a.email() != null) {
          return a.email();
        }
        return "";
      }
    });

    SafeHtmlBuilder html = new SafeHtmlBuilder();
    Iterator<? extends AccountInfo> itr = users.iterator();
    while (itr.hasNext()) {
      AccountInfo ai = itr.next();
      AvatarInfo img = ai.avatar(AvatarInfo.DEFAULT_SIZE);
      String name;
      if (ai.name() != null) {
        name = ai.name();
      } else if (ai.email() != null) {
        name = ai.email();
      } else {
        name = Integer.toString(ai._account_id());
      }

      html.openSpan();
      html.setAttribute("role", "listitem");
      html.setAttribute(DATA_ID, ai._account_id());
      html.setStyleName(style.label_user());
      if (img != null) {
        html.openElement("img");
        html.setStyleName(style.avatar());
        html.setAttribute("src", img.url());
        if (img.width() > 0) {
          html.setAttribute("width", img.width());
        }
        if (img.height() > 0) {
          html.setAttribute("height", img.height());
        }
        html.closeSelf();
      }
      html.append(name);
      if (removable.contains(ai._account_id())) {
        html.openElement("button");
        html.setAttribute("title", Util.M.removeReviewer(name));
        html.setAttribute("onclick", REMOVE + "(event)");
        html.append(new ImageResourceRenderer().render(Resources.I.remove_reviewer()));
      }
      html.closeSpan();
    }
    return html;
  }
}
