package com.eshop;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EshopMvpApplicationTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void catalogListsSeededItems() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/items").param("pageSize", "2").param("pageIndex", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageIndex", is(0)))
                .andExpect(jsonPath("$.count", is(13)))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void missingBasketReturnsEmptyBasket() throws Exception {
        mockMvc.perform(get("/api/v1/basket/demo-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerId", is("demo-user")))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void basketCheckoutCreatesOrderAndClearsBasket() throws Exception {
        mockMvc.perform(post("/api/v1/basket")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "buyerId": "demo-user",
                                  "items": [
                                    {
                                      "id": "line-1",
                                      "productId": 1,
                                      "productName": ".NET Bot Black Hoodie",
                                      "unitPrice": 19.50,
                                      "oldUnitPrice": 0,
                                      "quantity": 2,
                                      "pictureUrl": "http://localhost/api/v1/catalog/items/1/pic"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/basket/checkout")
                        .header("x-user-id", "demo-user")
                        .header("x-requestid", "00000000-0000-0000-0000-000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "city": "Redmond",
                                  "street": "1 Microsoft Way",
                                  "state": "WA",
                                  "country": "USA",
                                  "zipCode": "98052",
                                  "cardNumber": "4012888888881881",
                                  "cardHolderName": "Demo User",
                                  "cardExpiration": "2028-12-31T00:00:00Z",
                                  "cardSecurityNumber": "123",
                                  "cardTypeId": 2,
                                  "buyer": "demo-user"
                                }
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/orders").header("x-user-id", "demo-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("submitted")))
                .andExpect(jsonPath("$[0].total", is(39.0)));

        mockMvc.perform(get("/api/v1/basket/demo-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }
}
