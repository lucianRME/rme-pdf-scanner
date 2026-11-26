plugins { id("com.android.asset-pack") }
assetPack {
    packName.set(project.name)
    dynamicDelivery { deliveryType.set("on-demand") }
}
apply(from = rootProject.file("tools/m37/translation-asset-pack.gradle.kts"))
