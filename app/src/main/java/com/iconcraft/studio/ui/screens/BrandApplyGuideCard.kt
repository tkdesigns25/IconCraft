package com.iconcraft.studio.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iconcraft.studio.ui.theme.EmeraldAccent
import com.iconcraft.studio.ui.theme.IndigoPrimary
import com.iconcraft.studio.ui.theme.SlateSurface
import com.iconcraft.studio.ui.theme.SlateSurfaceVariant

data class BrandApplyInstructions(
    val brandTitle: String,
    val supportsNativeIconPack: Boolean,
    val step1: String,
    val step2: String
)

object BrandDetector {
    fun detectDeviceBrandGuide(sampleAppName: String = "Letterboxd"): BrandApplyInstructions {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val combined = "$manufacturer $brand"

        return when {
            combined.contains("nothing") -> BrandApplyInstructions(
                brandTitle = "Nothing OS Detected",
                supportsNativeIconPack = true,
                step1 = "1. Install the generated 'IconCraft Pack' APK when prompted.",
                step2 = "2. Long-press your Home Screen → Customization → Icon pack → Select 'IconCraft Pack'."
            )

            combined.contains("oneplus") || combined.contains("oppo") || combined.contains("realme") -> BrandApplyInstructions(
                brandTitle = "OnePlus / Oppo / Realme Detected",
                supportsNativeIconPack = true,
                step1 = "1. Install the generated 'IconCraft Pack' APK when prompted.",
                step2 = "2. Long-press your Home Screen → Icons → Select 'IconCraft Pack' (or long-press just $sampleAppName → Edit → tap the icon → choose from IconCraft Pack)."
            )

            combined.contains("samsung") -> BrandApplyInstructions(
                brandTitle = "Samsung One UI Detected",
                supportsNativeIconPack = true,
                step1 = "1. Install the generated 'IconCraft Pack' APK when prompted.",
                step2 = "2. Open Good Lock → Theme Park → Icon → Create New → Iconpack → Select 'IconCraft Pack'."
            )

            else -> BrandApplyInstructions(
                brandTitle = "Pixel / Motorola / Stock Launcher (${Build.MANUFACTURER})",
                supportsNativeIconPack = false,
                step1 = "1. Your stock launcher doesn't support third-party icon packs natively.",
                step2 = "2. Use the 'Add as 1×1 Home Widget' button below instead (or apply 'IconCraft Pack' in Nova, Lawnchair, or Smart Launcher)!"
            )
        }
    }
}

@Composable
fun BrandApplyGuideCard(
    sampleAppName: String = "Letterboxd",
    canPinWidget: Boolean = false,
    onPinWidgetClick: (() -> Unit)? = null
) {
    val guide = remember(sampleAppName) {
        BrandDetector.detectDeviceBrandGuide(sampleAppName)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (guide.supportsNativeIconPack) {
                    EmeraldAccent.copy(alpha = 0.4f)
                } else {
                    IndigoPrimary.copy(alpha = 0.5f)
                },
                shape = RoundedCornerShape(16.dp)
            ),
        color = SlateSurface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(IndigoPrimary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = IndigoPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = guide.brandTitle,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = "How to apply your selective custom icons",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateSurfaceVariant.copy(alpha = 0.65f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = guide.step1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = guide.step2,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (canPinWidget && onPinWidgetClick != null) {
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onPinWidgetClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add as 1×1 Home Widget (Zero Watermark)")
                }
            }
        }
    }
}
