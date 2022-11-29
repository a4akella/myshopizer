package com.salesmanager.core.business.services.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.apache.commons.collections4.CollectionUtils;
import org.jsoup.helper.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import com.salesmanager.core.business.configuration.ApplicationSearchConfiguration;
import com.salesmanager.core.business.constants.Constants;
import com.salesmanager.core.business.exception.ServiceException;
import com.salesmanager.core.business.services.catalog.pricing.PricingService;
import com.salesmanager.core.business.utils.CoreConfiguration;
import com.salesmanager.core.model.catalog.category.Category;
import com.salesmanager.core.model.catalog.category.CategoryDescription;
import com.salesmanager.core.model.catalog.product.Product;
import com.salesmanager.core.model.catalog.product.attribute.ProductAttribute;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionDescription;
import com.salesmanager.core.model.catalog.product.attribute.ProductOptionValueDescription;
import com.salesmanager.core.model.catalog.product.description.ProductDescription;
import com.salesmanager.core.model.catalog.product.image.ProductImage;
import com.salesmanager.core.model.catalog.product.manufacturer.Manufacturer;
import com.salesmanager.core.model.catalog.product.manufacturer.ManufacturerDescription;
import com.salesmanager.core.model.catalog.product.price.FinalPrice;
import com.salesmanager.core.model.catalog.product.variant.ProductVariant;
import com.salesmanager.core.model.merchant.MerchantStore;

import modules.commons.search.SearchModule;
import modules.commons.search.configuration.SearchConfiguration;
import modules.commons.search.request.Document;
import modules.commons.search.request.IndexItem;
import modules.commons.search.request.RequestOptions;
import modules.commons.search.request.SearchRequest;
import modules.commons.search.request.SearchResponse;

@Service("productSearchService")
@EnableConfigurationProperties(value = ApplicationSearchConfiguration.class)
public class SearchServiceImpl implements com.salesmanager.core.business.services.search.SearchService {
	
	
    @Value("${search.noindex:false}")//skip indexing process
    private boolean noIndex;

	private static final Logger LOGGER = LoggerFactory.getLogger(SearchServiceImpl.class);

	private final static String INDEX_PRODUCTS = "INDEX_PRODUCTS";
	
	
	/**
	 * TODO properties file
	 */
	
	private final static String KEYWORDS_MAPPING_DEFAULT = "{\"properties\":"
			+ "      {\n\"id\": {\n"
			+ "        \"type\": \"long\"\n"
			+ "      }\n"
			+ "     }\n"
			+ "    }";	
	
	private final static String PRODUCT_MAPPING_DEFAULT = "{\"properties\":"
			+ "      {\n\"id\": {\n"
			+ "        \"type\": \"long\"\n"
			+ "      },\n\"price\": {\n"
			+ "        \"type\": \"float\"\n"
			+ "      },\n\"description\": {\n"
			+ "        \"type\": \"text\"\n"
			+ "      },\n\"name\": {\n"
			+ "        \"type\": \"text\"\n"
			+ "      },\n\"category\": {\n"
			+ "        \"type\": \"keyword\", \n"
			+ "        \"normalizer\": \"custom_normalizer\"\n"
			+ "      },\n\"brand\": {\n"
			+ "        \"type\": \"keyword\" ,\n"
			+ "        \"normalizer\": \"custom_normalizer\"\n"
			+ "      },\n\"attributes\": {\n"
			+ "        \"type\": \"nested\"\n"
			+ "      },\n\"variants\": {\n"
			+ "        \"type\": \"nested\" \n"
			+ "      },\n\"store\": {\n"
			+ "        \"type\": \"keyword\" \n"
			+ "      },\n\"reviews\": {\n"
			+ "        \"type\": \"keyword\" \n"
			+ "      },\n\"image\": {\n"
			+ "        \"type\": \"keyword\"\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }";	
	private final static String SETTING_DEFAULT = "{\"analysis\": {\n"
			+ "      \"normalizer\": {\n"
			+ "        \"custom_normalizer\": {\n"
			+ "          \"type\": \"custom\",\n"
			+ "          \"char_filter\": [],\n"
			+ "          \"filter\": [\"lowercase\", \"asciifolding\"]\n"
			+ "        }\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }";
	private final static String SETTING_en = "{\"analysis\": {\n"
			+ "      \"normalizer\": {\n"
			+ "        \"custom_normalizer\": {\n"
			+ "          \"type\": \"custom\",\n"
			+ "          \"char_filter\": [],\n"
			+ "          \"filter\": [\"lowercase\", \"asciifolding\"]\n"
			+ "        }\n"
			+ "      }\n"
			+ "    }\n"
			+ "  }";

	@Inject
	private PricingService pricingService;

	@Inject
	private CoreConfiguration configuration;

	@Autowired
	private ApplicationSearchConfiguration applicationSearchConfiguration;

	@Autowired(required = false)
	private SearchModule searchModule;

	@PostConstruct
	public void init() {

		/**
		 * Configure search module
		 */

		if (searchModule != null && !noIndex) {

			SearchConfiguration searchConfiguration = config();
			try {
				searchModule.configure(searchConfiguration);
			} catch (Exception e) {
				LOGGER.error("SearchModule cannot be configured [" + e.getMessage() + "]", e);
			}
		}
	}

	public void index(MerchantStore store, Product product) throws ServiceException {

		Validate.notNull(product.getId(), "Product.id cannot be null");

		if (configuration.getProperty(INDEX_PRODUCTS) == null
				|| configuration.getProperty(INDEX_PRODUCTS).equals(Constants.FALSE) || searchModule == null) {
			return;
		}

		List<String> languages = languages(product);

		// existing documents
		List<Document> documents;
		List<Map<String, String>> variants = null;
		try {
			documents = document(product.getId(), languages, RequestOptions.DO_NOT_FAIL_ON_NOT_FOUND);

				if (!CollectionUtils.isEmpty(product.getVariants())) {
					variants = new ArrayList<Map<String, String>>();
					variants = product.getVariants().stream().map(i -> variation(i)).collect(Collectors.toList());
				}
	
				if (!CollectionUtils.isEmpty(documents)) {
					if (documents.iterator().next() != null) {
						searchModule.delete(languages, product.getId());
					}
				}


		} catch (Exception e) {
			throw new ServiceException(e);
		}

		Set<ProductDescription> descriptions = product.getDescriptions();

		for (ProductDescription description : descriptions) {
			indexProduct(store, description, product, variants);
		}

	}

	private List<Document> document(Long id, List<String> languages, RequestOptions options) throws Exception {
		List<Optional<Document>> documents = null;
		try {
			documents = searchModule.getDocument(id, languages, options);
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		for(Optional<Document> d : documents) {
			if(d == null) {//not allowed
				return Collections.emptyList();
			}
		}
		
		List<Document> filteredList = documents.stream().filter(Optional::isPresent).map(Optional::get)
				.collect(Collectors.toList());

		return filteredList;

	}

	private void indexProduct(MerchantStore store, ProductDescription description, Product product,
			List<Map<String, String>> variants) throws ServiceException {

		try {
			ProductImage image = null;
			if (!CollectionUtils.isEmpty(product.getImages())) {
				image = product.getImages().stream().filter(i -> i.isDefaultImage()).findFirst().get();
			}

			FinalPrice price = pricingService.calculateProductPrice(product);

			IndexItem item = new IndexItem();
			item.setId(product.getId());
			item.setStore(store.getCode().toLowerCase());
			item.setDescription(description.getDescription());
			item.setName(description.getName());
			item.setPrice(price.getStringPrice());

			if (product.getManufacturer() != null) {
				item.setBrand(manufacturer(product.getManufacturer(), description.getLanguage().getCode()));
			}

			if (!CollectionUtils.isEmpty(product.getCategories())) {
				item.setCategory(
						category(product.getCategories().iterator().next(), description.getLanguage().getCode()));
			}

			if (!CollectionUtils.isEmpty(product.getAttributes())) {
				Map<String, String> attributes = attributes(product, description.getLanguage().getCode());
				item.setAttributes(attributes);
			}

			if (image != null) {
				item.setImage(image.getProductImage());
			}

			if (product.getProductReviewAvg() != null) {
				item.setReviews(product.getProductReviewAvg().toString());
			}

			if (!CollectionUtils.isEmpty(variants)) {
				item.setVariants(variants);
			}

			item.setLanguage(description.getLanguage().getCode());

			searchModule.index(item);
		} catch (Exception e) {
			throw new ServiceException(e);
		}

	}

	private SearchConfiguration config() {

		SearchConfiguration config = new SearchConfiguration();
		config.setClusterName(applicationSearchConfiguration.getClusterName());
		config.setHosts(applicationSearchConfiguration.getHost());
		config.setCredentials(applicationSearchConfiguration.getCredentials());

		config.setLanguages(applicationSearchConfiguration.getSearchLanguages());
		
		config.getLanguages().stream().forEach(l -> this.mappings(config,l));
		config.getLanguages().stream().forEach(l -> this.settings(config,l));
		


		/**
		 * The mapping
		 */
		/*
		 * config.getProductMappings().put("variants", "nested");
		 * config.getProductMappings().put("attributes", "nested");
		 * config.getProductMappings().put("brand", "keyword");
		 * config.getProductMappings().put("store", "keyword");
		 * config.getProductMappings().put("reviews", "keyword");
		 * config.getProductMappings().put("image", "keyword");
		 * config.getProductMappings().put("category", "text");
		 * config.getProductMappings().put("name", "text");
		 * config.getProductMappings().put("description", "text");
		 * config.getProductMappings().put("price", "float");
		 * config.getProductMappings().put("id", "long");
		 
		config.getKeywordsMappings().put("store", "keyword");
		*/

		return config;

	}

	private Map<String, String> variation(ProductVariant instance) {
		if (instance == null)
			return null;

		Map<String, String> variantMap = new HashMap<String, String>();
		if (instance.getVariant() != null) {
			String variantCode = instance.getVariant().getProductOption().getCode();
			String variantValueCode = instance.getVariant().getProductOptionValue().getCode();

			variantMap.put(variantCode, variantValueCode);

		}

		if (instance.getVariantValue() != null) {
			String variantCode = instance.getVariantValue().getProductOption().getCode();
			String variantValueCode = instance.getVariantValue().getProductOptionValue().getCode();

			variantMap.put(variantCode, variantValueCode);
		}

		return variantMap;

	}

	@Override
	public void deleteDocument(MerchantStore store, Product product) throws ServiceException {

		if (configuration.getProperty(INDEX_PRODUCTS) == null
				|| configuration.getProperty(INDEX_PRODUCTS).equals(Constants.FALSE) || searchModule == null) {
			return;
		}

		List<String> languages = languages(product);

		try {
			searchModule.delete(languages, product.getId());
		} catch (Exception e) {
			throw new ServiceException(e);
		}

	}

	@Override
	public SearchResponse searchKeywords(MerchantStore store, String language, SearchRequest search, int entriesCount)
			throws ServiceException {

		if (configuration.getProperty(INDEX_PRODUCTS) == null
				|| configuration.getProperty(INDEX_PRODUCTS).equals(Constants.FALSE) || searchModule == null) {
			return null;
		}

		try {
			return searchModule.searchKeywords(search);
		} catch (Exception e) {
			throw new ServiceException(e);
		}
	}

	@Override
	public SearchResponse search(MerchantStore store, String language, SearchRequest search, int entriesCount,
			int startIndex) throws ServiceException {

		if (configuration.getProperty(INDEX_PRODUCTS) == null
				|| configuration.getProperty(INDEX_PRODUCTS).equals(Constants.FALSE) || searchModule == null) {
			return null;
		}

		try {
			return searchModule.searchProducts(search);
		} catch (Exception e) {
			throw new ServiceException(e);
		}

	}

	@Override
	public Optional<Document> getDocument(String language, MerchantStore store, Long productId)
			throws ServiceException {
		if (configuration.getProperty(INDEX_PRODUCTS) == null
				|| configuration.getProperty(INDEX_PRODUCTS).equals(Constants.FALSE) || searchModule == null) {
			return null;
		}

		try {
			return searchModule.getDocument(productId, language, RequestOptions.DEFAULT);
		} catch (Exception e) {
			throw new ServiceException(e);
		}
	}

	private List<String> languages(Product product) {
		return product.getDescriptions().stream().map(d -> d.getLanguage().getCode()).collect(Collectors.toList());
	}

	private String manufacturer(Manufacturer manufacturer, String language) {
		ManufacturerDescription description = manufacturer.getDescriptions().stream()
				.filter(d -> d.getLanguage().getCode().equals(language)).findFirst().get();
		return description.getName();
	}

	private String category(Category category, String language) {
		CategoryDescription description = category.getDescriptions().stream()
				.filter(d -> d.getLanguage().getCode().equals(language)).findFirst().get();
		return description.getName();
	}

	private Map<String, String> attributes(Product product, String language) {

		/**
		 * ProductAttribute ProductOption ProductOptionValue
		 */

		Map<String, String> allAttributes = new HashMap<String, String>();

		for (ProductAttribute attribute : product.getAttributes()) {
			Map<String, String> attr = attribute(attribute, language);
			allAttributes.putAll(attr);
		}

		return allAttributes;

	}

	private Map<String, String> attribute(ProductAttribute attribute, String language) {
		Map<String, String> attributeValue = new HashMap<String, String>();

		ProductOptionDescription optionDescription = attribute.getProductOption().getDescriptions().stream()
				.filter(a -> a.getLanguage().getCode().equals(language)).findFirst().get();		
				
		ProductOptionValueDescription value = attribute.getProductOptionValue().getDescriptions().stream()
				.filter(a -> a.getLanguage().getCode().equals(language)).findFirst().get();

		attributeValue.put(optionDescription.getName(), value.getName());

		return attributeValue;
	}
	
	private void settings(SearchConfiguration config, String language) {
		Validate.notEmpty(language, "Configuration requires language");
		String settings = this.SETTING_DEFAULT;
		//specific settings
		if(language.equals("en")) {
			settings = this.SETTING_en;
		}
		
		config.getSettings().put(language, settings);

	}
	
	private void mappings(SearchConfiguration config, String language) {
		Validate.notEmpty(language, "Configuration requires language");


		config.getProductMappings().put(language, PRODUCT_MAPPING_DEFAULT);
		config.getKeywordsMappings().put(language, KEYWORDS_MAPPING_DEFAULT);
			
	}

}
