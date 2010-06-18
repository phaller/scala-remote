/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.apis.view

import com.example.android.apis.R

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.{ListView, ArrayAdapter}

/**
 * Demonstrates the use of non-focusable views.
 */
class Focus1 extends Activity {

  override protected def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.focus_1)

    val webView = findViewById(R.id.rssWebView).asInstanceOf[WebView]
    webView.loadData(<html><body>Can I focus?<br /><a href="#">No I cannot!</a>.</body></html>.toString, "text/html", "utf-8")

    val listView = findViewById(R.id.rssListView).asInstanceOf[ListView]
    listView setAdapter new ArrayAdapter[String](this,
                android.R.layout.simple_list_item_1, 
                Array("Ars Technica", "Slashdot", "GameKult"))
  }
}
