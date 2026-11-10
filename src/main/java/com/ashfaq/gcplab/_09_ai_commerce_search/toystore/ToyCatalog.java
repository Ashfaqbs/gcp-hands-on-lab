package com.ashfaq.gcplab._09_ai_commerce_search.toystore;

import com.google.cloud.retail.v2.CustomAttribute;
import com.google.cloud.retail.v2.PriceInfo;
import com.google.cloud.retail.v2.Product;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 20 hand-written toys, same Product shape as the 703-item supermarket
 * catalog (id, title, description, category, brand, price, attributes).
 * Descriptions are deliberately varied so semantic search has something to
 * match beyond the word "toy".
 */
public final class ToyCatalog {

    public static final String PROJECT_ID = "project-3d2fd1eb-6dd8-40b6-958";
    public static final String CATALOG =
            "projects/" + PROJECT_ID + "/locations/global/catalogs/default_catalog";
    public static final String BRANCH = CATALOG + "/branches/0";
    public static final String SERVING_CONFIG = CATALOG + "/servingConfigs/default_search";

    private ToyCatalog() {
    }

    private record Toy(String id, String title, String description, String category,
                       String brand, float price, String ageRange) {
    }

    private static final List<Toy> TOYS = List.of(
            new Toy("toy-1", "Classic Wooden Building Blocks (50 pcs)", "Colourful wooden blocks for stacking and building towers. Safe non-toxic paint.", "Toys > Building", "WoodWorks", 599f, "3+"),
            new Toy("toy-2", "Cuddly Teddy Bear Plush", "Soft brown plush teddy bear, perfect for hugging and bedtime.", "Toys > Plush", "SoftHug", 449f, "0+"),
            new Toy("toy-3", "Remote Control Racing Car", "Fast rechargeable remote control car with rubber tyres, runs on floors and carpets.", "Toys > Vehicles", "SpeedKing", 1299f, "6+"),
            new Toy("toy-4", "Jigsaw Puzzle World Map (200 pcs)", "Educational jigsaw puzzle showing countries and capitals.", "Toys > Puzzles", "BrainBox", 349f, "7+"),
            new Toy("toy-5", "Toy Kitchen Play Set", "Pretend-play kitchen with pots, pans, and play food.", "Toys > Pretend Play", "LittleChef", 1499f, "3+"),
            new Toy("toy-6", "Dinosaur Action Figure Set (6 pcs)", "Six realistic dinosaur figures including T-Rex and Triceratops.", "Toys > Figures", "JurassicJr", 799f, "4+"),
            new Toy("toy-7", "Doll House with Furniture", "Two-storey doll house with tiny furniture and a family of four dolls.", "Toys > Dolls", "DreamHome", 1899f, "4+"),
            new Toy("toy-8", "Beginner Science Experiment Kit", "Twenty safe experiments: volcano, slime, crystals. Teaches basic science.", "Toys > Educational", "BrainBox", 999f, "8+"),
            new Toy("toy-9", "Musical Xylophone for Toddlers", "Eight-note colourful xylophone with two mallets. Develops rhythm and hearing.", "Toys > Musical", "TinyTunes", 399f, "2+"),
            new Toy("toy-10", "Snakes and Ladders Board Game", "Classic family board game for two to four players.", "Toys > Games", "FunTime", 249f, "5+"),
            new Toy("toy-11", "Fire Truck with Lights and Sounds", "Battery-powered fire engine with siren, flashing lights and extending ladder.", "Toys > Vehicles", "SpeedKing", 899f, "3+"),
            new Toy("toy-12", "Soft Rabbit Plush Toy", "Floppy-eared white rabbit plush, machine washable.", "Toys > Plush", "SoftHug", 329f, "0+"),
            new Toy("toy-13", "Interlocking Plastic Bricks (300 pcs)", "Compatible construction bricks to build houses, cars and robots.", "Toys > Building", "BrickMaster", 1199f, "6+"),
            new Toy("toy-14", "Fashion Doll with Dress Set", "Twelve-inch fashion doll with three outfits and accessories.", "Toys > Dolls", "GlamGirl", 699f, "5+"),
            new Toy("toy-15", "Kids Art and Craft Kit", "Crayons, paints, glitter glue and paper for creative drawing.", "Toys > Educational", "ArtyKids", 549f, "4+"),
            new Toy("toy-16", "Toy Doctor Medical Kit", "Stethoscope, thermometer and syringe for pretend doctor play.", "Toys > Pretend Play", "LittleChef", 499f, "3+"),
            new Toy("toy-17", "Pull-Back Toy Cars (Set of 4)", "Four small friction cars that zoom when pulled back and released.", "Toys > Vehicles", "SpeedKing", 299f, "3+"),
            new Toy("toy-18", "Memory Match Card Game", "Forty-eight picture cards for a fun memory and matching game.", "Toys > Games", "FunTime", 199f, "4+"),
            new Toy("toy-19", "Stacking Rings Baby Toy", "Rainbow stacking rings on a base, helps baby learn colours and grip.", "Toys > Baby", "TinyTunes", 279f, "1+"),
            new Toy("toy-20", "Robot Building Kit with Motor", "Assemble a walking robot with a small motor. Introduces basic engineering.", "Toys > Educational", "BrickMaster", 1599f, "9+")
    );

    public static List<Product> all() {
        List<Product> products = new ArrayList<>();
        for (Toy t : TOYS) {
            products.add(toProduct(t));
        }
        return List.copyOf(products);
    }

    /** The 20 toys keyed by id. This stands in for "our own database" that the UI reads titles and prices from. */
    public static Map<String, Product> byId() {
        Map<String, Product> map = new LinkedHashMap<>();
        for (Product product : all()) {
            map.put(product.getId(), product);
        }
        return Map.copyOf(map);
    }

    public static Product extraToy() {
        return toProduct(new Toy("toy-21", "Wooden Train Track Set (40 pcs)",
                "Wooden railway with an engine, carriages, bridges and curved track pieces.",
                "Toys > Vehicles", "WoodWorks", 1399f, "3+"));
    }

    public static String productName(String id) {
        return BRANCH + "/products/" + id;
    }

    private static Product toProduct(Toy t) {
        return Product.newBuilder()
                .setId(t.id())
                .setTitle(t.title())
                .setDescription(t.description())
                .addCategories(t.category())
                .addBrands(t.brand())
                .setPriceInfo(PriceInfo.newBuilder().setCurrencyCode("INR").setPrice(t.price()).build())
                .setAvailability(Product.Availability.IN_STOCK)
                .putAttributes("age_range", CustomAttribute.newBuilder().addText(t.ageRange()).build())
                .build();
    }
}
