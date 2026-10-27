package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
class Menu(
    val menuRenderer: MenuRenderer,
) {
    @Serializable
    class MenuRenderer(
        val items: List<Item>?,
        val topLevelButtons: List<TopLevelButton>?,
    ) {
        @Serializable
        class Item(
            val menuNavigationItemRenderer: MenuNavigationItemRenderer?,
            val menuServiceItemRenderer: MenuServiceItemRenderer?,
            val toggleMenuServiceItemRenderer: ToggleMenuServiceRenderer?,
        ) {
            @Serializable
            class MenuNavigationItemRenderer(
                val text: Runs,
                val icon: Icon,
                val navigationEndpoint: NavigationEndpoint,
            )

            @Serializable
            class MenuServiceItemRenderer(
                val text: Runs,
                val icon: Icon,
                val serviceEndpoint: NavigationEndpoint,
            )
            @Serializable
            class ToggleMenuServiceRenderer(
                val defaultIcon: Icon,
                val defaultServiceEndpoint: DefaultServiceEndpoint,
                val toggledServiceEndpoint: ToggledServiceEndpoint?,
                val isSelected: Boolean = false,
            )
        }

        @Serializable
        class TopLevelButton(
            val buttonRenderer: ButtonRenderer?,
        ) {
            @Serializable
            class ButtonRenderer(
                val icon: Icon,
                val navigationEndpoint: NavigationEndpoint,
            )
        }
    }
}
