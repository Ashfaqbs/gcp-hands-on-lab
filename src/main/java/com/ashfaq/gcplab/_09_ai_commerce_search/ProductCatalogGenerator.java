package com.ashfaq.gcplab._09_ai_commerce_search;

import com.google.cloud.retail.v2.CustomAttribute;
import com.google.cloud.retail.v2.PriceInfo;
import com.google.cloud.retail.v2.Product;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a synthetic ~600-800 item catalog modeled on a generic local
 * supermarket (groceries, household, personal care, apparel, etc). Not
 * random gibberish - every item is a deliberate, realistic product so
 * later search-quality scoring has real ground truth to judge against.
 * Variety comes from three dimensions multiplied together: item x
 * weight/size variant x brand.
 */
public final class ProductCatalogGenerator {

    private ProductCatalogGenerator() {
    }

    record Template(String category, String item, String description,
                     String[] variants, String variantAttribute, String[] brands, double basePrice) {
    }

    private static final String[] GROCERY_BRANDS = {"Farmland", "PureHarvest", "GoldenGrain", "DailyFresh"};
    private static final String[] DAIRY_BRANDS = {"MeadowFresh", "PureHarvest", "DailyFresh", "CreamyDale"};
    private static final String[] SNACK_BRANDS = {"CrunchTime", "MunchBox", "TastyBite", "GoldenGrain"};
    private static final String[] HOUSEHOLD_BRANDS = {"SparkleClean", "HomeShine", "PureSoft"};
    private static final String[] PERSONAL_CARE_BRANDS = {"GlowCare", "PureSoft", "NatureBliss"};
    private static final String[] BABY_BRANDS = {"LittleOnes", "TinyCare", "SoftHug"};
    private static final String[] APPAREL_BRANDS = {"UrbanThread", "ComfortWear", "StyleHub", "DailyFit"};
    private static final String[] HEALTH_BRANDS = {"VitaWell", "PureHarvest", "NatureBliss"};

    private static final String[] WEIGHTS_SMALL = {"100g", "250g", "500g"};
    private static final String[] WEIGHTS_GRAIN = {"1kg", "5kg", "10kg"};
    private static final String[] VOLUMES_SMALL = {"200ml", "500ml", "1L"};
    private static final String[] VOLUMES_LARGE = {"1L", "2L", "5L"};
    private static final String[] APPAREL_SIZES = {"S", "M", "L", "XL", "XXL"};

    public static List<Product> generate() {
        List<Template> templates = new ArrayList<>();

        // Fruits & Vegetables - sold per item/kg, no brand/weight variants (single listing each)
        for (String v : new String[]{
                "Banana", "Apple", "Onion", "Potato", "Tomato", "Carrot", "Spinach", "Cucumber",
                "Orange", "Mango", "Grapes", "Cabbage", "Cauliflower", "Green Chilli", "Ginger",
                "Garlic", "Lemon", "Watermelon", "Papaya", "Pineapple"}) {
            templates.add(new Template("Fruits & Vegetables", v, "Fresh " + v.toLowerCase() + ", sold per kg.",
                    new String[]{"per kg"}, "unit", new String[]{"Farm Fresh"}, 40));
        }

        // Dairy & Eggs
        templates.add(new Template("Dairy & Eggs", "Toned Milk", "Fresh toned milk.", VOLUMES_SMALL, "volume", DAIRY_BRANDS, 30));
        templates.add(new Template("Dairy & Eggs", "Curd", "Thick set curd/yogurt.", WEIGHTS_SMALL, "weight", DAIRY_BRANDS, 25));
        templates.add(new Template("Dairy & Eggs", "Paneer", "Soft fresh paneer cubes.", new String[]{"200g", "500g"}, "weight", DAIRY_BRANDS, 60));
        templates.add(new Template("Dairy & Eggs", "Butter", "Salted table butter.", new String[]{"100g", "500g"}, "weight", DAIRY_BRANDS, 45));
        templates.add(new Template("Dairy & Eggs", "Cheese Slices", "Processed cheese slices.", new String[]{"100g", "200g"}, "weight", DAIRY_BRANDS, 90));
        templates.add(new Template("Dairy & Eggs", "Eggs", "Farm eggs, tray pack.", new String[]{"6 pack", "12 pack", "30 pack"}, "count", DAIRY_BRANDS, 60));

        // Bakery
        templates.add(new Template("Bakery", "White Bread", "Soft sandwich bread loaf.", new String[]{"400g", "700g"}, "weight", GROCERY_BRANDS, 35));
        templates.add(new Template("Bakery", "Brown Bread", "Whole wheat brown bread.", new String[]{"400g", "700g"}, "weight", GROCERY_BRANDS, 40));
        templates.add(new Template("Bakery", "Butter Cookies", "Crunchy butter cookies.", WEIGHTS_SMALL, "weight", SNACK_BRANDS, 50));
        templates.add(new Template("Bakery", "Rusk", "Toasted bread rusk.", new String[]{"200g", "500g"}, "weight", SNACK_BRANDS, 40));

        // Snacks
        templates.add(new Template("Snacks", "Potato Chips", "Crispy salted potato chips.", WEIGHTS_SMALL, "weight", SNACK_BRANDS, 20));
        templates.add(new Template("Snacks", "Namkeen Mixture", "Spicy fried namkeen mixture.", new String[]{"200g", "500g", "1kg"}, "weight", SNACK_BRANDS, 45));
        templates.add(new Template("Snacks", "Cream Biscuits", "Chocolate cream biscuits.", WEIGHTS_SMALL, "weight", SNACK_BRANDS, 30));
        templates.add(new Template("Snacks", "Milk Chocolate Bar", "Smooth milk chocolate bar.", new String[]{"50g", "100g"}, "weight", SNACK_BRANDS, 60));
        templates.add(new Template("Snacks", "Popcorn Kernels", "Butter popcorn kernels.", new String[]{"200g", "500g"}, "weight", SNACK_BRANDS, 55));

        // Beverages
        templates.add(new Template("Beverages", "Cola Soft Drink", "Carbonated cola soft drink.", new String[]{"250ml", "500ml", "1L", "2L"}, "volume", SNACK_BRANDS, 40));
        templates.add(new Template("Beverages", "Mixed Fruit Juice", "No added sugar fruit juice.", new String[]{"200ml", "1L"}, "volume", GROCERY_BRANDS, 50));
        templates.add(new Template("Beverages", "Packaged Mineral Water", "Purified mineral drinking water.", new String[]{"500ml", "1L", "2L", "5L"}, "volume", GROCERY_BRANDS, 20));
        templates.add(new Template("Beverages", "Black Tea Leaves", "Strong CTC black tea leaves.", new String[]{"100g", "250g", "500g"}, "weight", GROCERY_BRANDS, 90));
        templates.add(new Template("Beverages", "Instant Coffee", "Rich instant coffee powder.", WEIGHTS_SMALL, "weight", GROCERY_BRANDS, 120));

        // Grocery & Staples
        templates.add(new Template("Grocery & Staples", "Basmati Rice", "Long grain aged basmati rice.", new String[]{"1kg", "5kg", "10kg", "25kg"}, "weight", GROCERY_BRANDS, 90));
        templates.add(new Template("Grocery & Staples", "Wheat Flour Atta", "Whole wheat chakki atta.", WEIGHTS_GRAIN, "weight", GROCERY_BRANDS, 45));
        templates.add(new Template("Grocery & Staples", "Refined Sugar", "Fine refined white sugar.", new String[]{"1kg", "5kg"}, "weight", GROCERY_BRANDS, 45));
        templates.add(new Template("Grocery & Staples", "Iodised Salt", "Free flow iodised salt.", new String[]{"1kg"}, "weight", GROCERY_BRANDS, 20));
        templates.add(new Template("Grocery & Staples", "Toor Dal", "Split pigeon pea lentils.", new String[]{"500g", "1kg", "2kg"}, "weight", GROCERY_BRANDS, 130));
        templates.add(new Template("Grocery & Staples", "Sunflower Cooking Oil", "Refined sunflower cooking oil.", new String[]{"500ml", "1L", "5L"}, "volume", GROCERY_BRANDS, 110));
        templates.add(new Template("Grocery & Staples", "Turmeric Powder", "Pure ground turmeric powder.", new String[]{"50g", "100g", "200g"}, "weight", GROCERY_BRANDS, 30));
        templates.add(new Template("Grocery & Staples", "Red Chilli Powder", "Hot red chilli powder.", new String[]{"50g", "100g", "200g"}, "weight", GROCERY_BRANDS, 35));

        // Household & Cleaning
        templates.add(new Template("Household & Cleaning", "Detergent Powder", "High foam detergent powder.", new String[]{"500g", "1kg", "2kg", "5kg"}, "weight", HOUSEHOLD_BRANDS, 90));
        templates.add(new Template("Household & Cleaning", "Dishwash Liquid Gel", "Lemon dishwash liquid gel.", new String[]{"250ml", "500ml", "1L"}, "volume", HOUSEHOLD_BRANDS, 60));
        templates.add(new Template("Household & Cleaning", "Floor Cleaner", "Disinfectant floor cleaner.", VOLUMES_SMALL, "volume", HOUSEHOLD_BRANDS, 80));
        templates.add(new Template("Household & Cleaning", "Toilet Cleaner", "Thick toilet bowl cleaner.", VOLUMES_SMALL, "volume", HOUSEHOLD_BRANDS, 75));
        templates.add(new Template("Household & Cleaning", "Facial Tissue Box", "Soft facial tissue box.", new String[]{"100 pulls", "200 pulls"}, "count", HOUSEHOLD_BRANDS, 60));

        // Personal Care
        templates.add(new Template("Personal Care", "Anti-Dandruff Shampoo", "Cooling anti-dandruff shampoo.", new String[]{"200ml", "340ml", "650ml"}, "volume", PERSONAL_CARE_BRANDS, 120));
        templates.add(new Template("Personal Care", "Bath Soap Bar", "Moisturising bath soap bar.", new String[]{"75g", "125g"}, "weight", PERSONAL_CARE_BRANDS, 35));
        templates.add(new Template("Personal Care", "Toothpaste", "Cavity protection toothpaste.", new String[]{"100g", "150g"}, "weight", PERSONAL_CARE_BRANDS, 55));
        templates.add(new Template("Personal Care", "Body Wash", "Refreshing body wash gel.", new String[]{"250ml", "500ml"}, "volume", PERSONAL_CARE_BRANDS, 130));
        templates.add(new Template("Personal Care", "Face Wash", "Oil control face wash.", new String[]{"50g", "100g"}, "weight", PERSONAL_CARE_BRANDS, 90));
        templates.add(new Template("Personal Care", "Hair Oil", "Nourishing coconut hair oil.", new String[]{"100ml", "200ml"}, "volume", PERSONAL_CARE_BRANDS, 70));

        // Baby Care - includes clothing-style SIZE variants (S/M/L/XL) for diapers
        templates.add(new Template("Baby Care", "Baby Diapers", "Soft ultra-absorbent baby diapers.", APPAREL_SIZES, "size", BABY_BRANDS, 350));
        templates.add(new Template("Baby Care", "Baby Wipes", "Gentle fragrance-free baby wipes.", new String[]{"1 pack", "3 pack"}, "count", BABY_BRANDS, 90));
        templates.add(new Template("Baby Care", "Baby Powder", "Talc-free baby powder.", new String[]{"100g", "200g"}, "weight", BABY_BRANDS, 80));
        templates.add(new Template("Baby Care", "Baby Lotion", "Mild moisturising baby lotion.", new String[]{"100ml", "200ml"}, "volume", BABY_BRANDS, 95));

        // Health & Wellness
        templates.add(new Template("Health & Wellness", "Multivitamin Tablets", "Daily multivitamin tablets.", new String[]{"30 tablets", "60 tablets"}, "count", HEALTH_BRANDS, 180));
        templates.add(new Template("Health & Wellness", "Hand Sanitizer", "Alcohol based hand sanitizer.", new String[]{"100ml", "500ml"}, "volume", HEALTH_BRANDS, 60));
        templates.add(new Template("Health & Wellness", "Whey Protein Powder", "Whey protein powder supplement.", new String[]{"500g", "1kg", "2kg"}, "weight", HEALTH_BRANDS, 900));
        templates.add(new Template("Health & Wellness", "Pain Relief Spray", "Fast acting pain relief spray.", new String[]{"100ml"}, "volume", HEALTH_BRANDS, 150));

        // Apparel & Footwear - all SIZE variants (the "XL"-style search bucket)
        templates.add(new Template("Apparel", "Men's Cotton T-Shirt", "Round neck cotton t-shirt.", APPAREL_SIZES, "size", APPAREL_BRANDS, 400));
        templates.add(new Template("Apparel", "Women's Cotton T-Shirt", "Casual cotton round neck t-shirt.", APPAREL_SIZES, "size", APPAREL_BRANDS, 400));
        templates.add(new Template("Apparel", "Men's Formal Shirt", "Slim fit formal cotton shirt.", APPAREL_SIZES, "size", APPAREL_BRANDS, 800));
        templates.add(new Template("Apparel", "Women's Kurti", "Printed straight-fit kurti.", APPAREL_SIZES, "size", APPAREL_BRANDS, 650));
        templates.add(new Template("Apparel", "Men's Track Pants", "Slim fit jogger track pants.", APPAREL_SIZES, "size", APPAREL_BRANDS, 550));
        templates.add(new Template("Apparel", "Women's Leggings", "Stretchable ankle-length leggings.", APPAREL_SIZES, "size", APPAREL_BRANDS, 350));
        templates.add(new Template("Apparel", "Kids T-Shirt", "Printed cotton t-shirt for kids.", APPAREL_SIZES, "size", APPAREL_BRANDS, 300));
        templates.add(new Template("Apparel", "Men's Denim Jeans", "Slim fit denim jeans.", APPAREL_SIZES, "size", APPAREL_BRANDS, 1200));
        templates.add(new Template("Apparel", "Men's Innerwear Vest", "Cotton rib vest, pack of 3.", APPAREL_SIZES, "size", APPAREL_BRANDS, 450));
        templates.add(new Template("Apparel", "Women's Nightwear Set", "Cotton night suit set.", APPAREL_SIZES, "size", APPAREL_BRANDS, 700));

        // Frozen Foods
        templates.add(new Template("Frozen Foods", "Frozen Green Peas", "Farm frozen green peas.", new String[]{"500g", "1kg"}, "weight", GROCERY_BRANDS, 80));
        templates.add(new Template("Frozen Foods", "Frozen Paratha", "Ready to cook frozen paratha.", new String[]{"5 pieces", "10 pieces"}, "count", GROCERY_BRANDS, 100));
        templates.add(new Template("Frozen Foods", "Vanilla Ice Cream", "Creamy vanilla ice cream tub.", new String[]{"500ml", "1L"}, "volume", DAIRY_BRANDS, 150));
        templates.add(new Template("Frozen Foods", "Frozen Veg Nuggets", "Crispy frozen vegetable nuggets.", new String[]{"250g", "500g"}, "weight", GROCERY_BRANDS, 110));

        // Home/misc filler - single listing each, no variant
        for (String v : new String[]{
                "Ruled Notebook", "Ball Pen Set", "Pencil Box", "Scented Candle", "LED Light Bulb",
                "AA Batteries Pack", "Mobile Phone Charger", "Folding Umbrella", "Steel Water Bottle", "Plastic Lunch Box"}) {
            templates.add(new Template("Home & Stationery", v, "Everyday " + v.toLowerCase() + " for home and office use.",
                    new String[]{"standard"}, "unit", new String[]{"HomeShine"}, 150));
        }

        return expand(templates);
    }

    private static List<Product> expand(List<Template> templates) {
        List<Product> products = new ArrayList<>();
        int idCounter = 1;

        for (Template t : templates) {
            for (String brand : t.brands()) {
                for (String variant : t.variants()) {
                    String title = brand + " " + t.item() + (variant.equals("standard") || variant.equals("per kg") ? "" : " (" + variant + ")");
                    String id = "p" + (idCounter++);

                    Product.Builder builder = Product.newBuilder()
                            .setId(id)
                            .setTitle(title)
                            .setDescription(t.description() + " Brand: " + brand + ".")
                            .addCategories(t.category())
                            .addBrands(brand)
                            .setPriceInfo(PriceInfo.newBuilder()
                                    .setCurrencyCode("INR")
                                    .setPrice((float) (t.basePrice() + (variant.hashCode() % 50)))
                                    .build())
                            .setAvailability(Product.Availability.IN_STOCK);

                    if (!t.variantAttribute().equals("unit")) {
                        builder.putAttributes(t.variantAttribute(),
                                CustomAttribute.newBuilder().addText(variant).build());
                    }

                    products.add(builder.build());
                }
            }
        }
        return products;
    }
}
