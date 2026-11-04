package com.example.ui.screens.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.NavTab

@Composable
fun ProfileRoute(
    hiddenTabs: List<NavTab> = emptyList(),
    tabDefinitions: Map<NavTab, Pair<Int, ImageVector>> = emptyMap(),
    onNavigateToTab: (NavTab) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ProfileScreen(
            state = ProfileUiState(isLoading = true),
            actions = ProfileActions.NoOp,
            contentPadding = contentPadding
        )

        if (hiddenTabs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = contentPadding.calculateBottomPadding() + 20.dp)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.profile_hidden_tabs_header),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                for (tab in hiddenTabs) {
                    val def = tabDefinitions[tab] ?: continue
                    val tabLabel = stringResource(def.first)
                    Button(
                        onClick = { onNavigateToTab(tab) },
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = def.second,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = tabLabel)
                    }
                }
            }
        }
    }
}
