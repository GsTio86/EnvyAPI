package com.envyful.api.forge.config;

import com.envyful.api.config.type.ConfigItem;
import com.envyful.api.forge.chat.UtilChatColour;
import com.envyful.api.forge.items.ItemBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;

import java.util.Map;

public class UtilConfigItem {

    public static ItemStack fromConfigItem(ConfigItem configItem) {
        ItemBuilder itemBuilder = new ItemBuilder()
                .type(Item.getByNameOrId(configItem.getType()))
                .amount(configItem.getAmount())
                .name(UtilChatColour.translateColourCodes('&', configItem.getName()))
                .damage(configItem.getDamage());

        for (String s : configItem.getLore()) {
            itemBuilder.addLore(UtilChatColour.translateColourCodes('&', s));
        }

        for (Map.Entry<String, ConfigItem.NBTValue> nbtData : configItem.getNbt().entrySet()) {
            NBTBase base = null;
            switch (nbtData.getValue().getType().toLowerCase()) {
                case "int" : case "integer" :
                    base = new NBTTagInt(Integer.parseInt(nbtData.getValue().getData()));
                    break;
                case "long" :
                    base = new NBTTagLong(Long.parseLong(nbtData.getValue().getData()));
                    break;
                case "byte" :
                    base = new NBTTagByte(Byte.parseByte(nbtData.getValue().getData()));
                    break;
                case "double" :
                    base = new NBTTagDouble(Double.parseDouble(nbtData.getValue().getData()));
                    break;
                case "float" :
                    base = new NBTTagFloat(Float.parseFloat(nbtData.getValue().getData()));
                    break;
                case "short" :
                    base = new NBTTagShort(Short.parseShort(nbtData.getValue().getData()));
                    break;
                default : case "string" :
                    base = new NBTTagString(nbtData.getValue().getData());
                    break;
            }

            itemBuilder.nbt(nbtData.getKey(), base);
        }

        return itemBuilder.build();
    }

}