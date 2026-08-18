import React, { FormEvent, useEffect, useMemo, useState } from 'react';
import Keycloak from 'keycloak-js';
import {
  askAssistant,
  CartLine,
  createOrder,
  fallbackProducts,
  getProducts,
  Product,
  searchProducts,
} from './api';
import './App.css';

const categories = [
  { id: 1, name: 'Electronics' },
  { id: 2, name: 'Clothing' },
  { id: 3, name: 'Home & Garden' },
  { id: 4, name: 'Books' },
  { id: 5, name: 'Sports' },
];

const keycloak = new Keycloak({
  url: process.env.REACT_APP_KEYCLOAK_URL ?? 'http://localhost:8180',
  realm: process.env.REACT_APP_KEYCLOAK_REALM ?? 'ecommerce',
  clientId: process.env.REACT_APP_KEYCLOAK_CLIENT_ID ?? 'swagger-ui',
});
let keycloakInit: Promise<boolean> | undefined;

const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

function App() {
  const [products, setProducts] = useState<Product[]>(fallbackProducts.filter((p) => p.categoryId === 1));
  const [category, setCategory] = useState(1);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [usingDemoData, setUsingDemoData] = useState(false);
  const [cart, setCart] = useState<CartLine[]>(() => {
    try {
      return JSON.parse(localStorage.getItem('shopease-cart') ?? '[]');
    } catch {
      return [];
    }
  });
  const [cartOpen, setCartOpen] = useState(false);
  const [checkoutOpen, setCheckoutOpen] = useState(false);
  const [shippingAddress, setShippingAddress] = useState('');
  const [checkoutBusy, setCheckoutBusy] = useState(false);
  const [authenticated, setAuthenticated] = useState(false);
  const [authReady, setAuthReady] = useState(false);
  const [notice, setNotice] = useState('');
  const [assistantOpen, setAssistantOpen] = useState(false);
  const [assistantInput, setAssistantInput] = useState('');
  const [assistantBusy, setAssistantBusy] = useState(false);
  const [messages, setMessages] = useState([
    { from: 'assistant', text: 'Hi! I can help you find the right product.' },
  ]);

  useEffect(() => {
    if (process.env.NODE_ENV === 'test') {
      setAuthReady(true);
      return;
    }
    keycloakInit ??= keycloak.init({ onLoad: 'check-sso', pkceMethod: 'S256' });
    keycloakInit
      .then((result) => setAuthenticated(result))
      .catch(() => setAuthenticated(false))
      .finally(() => setAuthReady(true));
  }, []);

  useEffect(() => {
    let active = true;
    setLoading(true);
    getProducts(category)
      .then((data) => {
        if (!active) return;
        setProducts(data.length ? data : fallbackProducts.filter((p) => p.categoryId === category));
        setUsingDemoData(data.length === 0);
      })
      .catch(() => {
        if (!active) return;
        setProducts(fallbackProducts.filter((p) => p.categoryId === category));
        setUsingDemoData(true);
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [category]);

  useEffect(() => {
    localStorage.setItem('shopease-cart', JSON.stringify(cart));
  }, [cart]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(''), 4000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const cartCount = cart.reduce((sum, line) => sum + line.quantity, 0);
  const subtotal = useMemo(
    () => cart.reduce((sum, line) => sum + (line.salePrice ?? line.price) * line.quantity, 0),
    [cart],
  );

  const addToCart = (product: Product) => {
    setCart((current) => {
      const existing = current.find((line) => line.id === product.id);
      return existing
        ? current.map((line) => line.id === product.id
          ? { ...line, quantity: Math.min(line.quantity + 1, product.stockQuantity) }
          : line)
        : [...current, { ...product, quantity: 1 }];
    });
    setNotice(`${product.name} added to your bag`);
  };

  const changeQuantity = (id: string, delta: number) => {
    setCart((current) => current
      .map((line) => line.id === id
        ? { ...line, quantity: Math.max(0, Math.min(line.quantity + delta, line.stockQuantity)) }
        : line)
      .filter((line) => line.quantity > 0));
  };

  const runSearch = async (event: FormEvent) => {
    event.preventDefault();
    if (!query.trim()) return;
    setLoading(true);
    try {
      const result = await searchProducts(query.trim());
      setProducts(result);
      setUsingDemoData(false);
    } catch {
      const term = query.toLowerCase();
      setProducts(fallbackProducts.filter((p) => p.name.toLowerCase().includes(term)));
      setUsingDemoData(true);
    } finally {
      setLoading(false);
    }
  };

  const beginCheckout = () => {
    if (!authenticated) {
      keycloak.login({ redirectUri: window.location.href });
      return;
    }
    setCartOpen(false);
    setCheckoutOpen(true);
  };

  const submitOrder = async (event: FormEvent) => {
    event.preventDefault();
    const userId = String(keycloak.tokenParsed?.sub ?? '');
    const email = String(keycloak.tokenParsed?.email ?? '');
    if (!keycloak.token || !userId || !email) {
      setNotice('Your login is missing required profile details. Please sign in again.');
      return;
    }

    setCheckoutBusy(true);
    try {
      const order = await createOrder(cart, shippingAddress, { userId, email }, keycloak.token);
      setCart([]);
      setCheckoutOpen(false);
      setNotice(`Order ${order.id.slice(0, 8)} received — payment is processing.`);
    } catch {
      setNotice('Checkout is unavailable. Make sure the ShopEase services are running.');
    } finally {
      setCheckoutBusy(false);
    }
  };

  const sendAssistantMessage = async (event: FormEvent) => {
    event.preventDefault();
    const text = assistantInput.trim();
    if (!text) return;
    setAssistantInput('');
    setMessages((current) => [...current, { from: 'user', text }]);
    setAssistantBusy(true);
    try {
      const result = await askAssistant('storefront-session', text, keycloak.token);
      setMessages((current) => [...current, { from: 'assistant', text: result.response }]);
    } catch {
      setMessages((current) => [...current, {
        from: 'assistant',
        text: 'The AI service is offline right now. Try browsing the categories or searching above.',
      }]);
    } finally {
      setAssistantBusy(false);
    }
  };

  return (
    <div className="app">
      <header className="site-header">
        <a className="brand" href="/" aria-label="ShopEase home">
          <span className="brand-mark">S</span>
          <span>ShopEase</span>
        </a>
        <form className="search" onSubmit={runSearch}>
          <span aria-hidden="true">⌕</span>
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search products"
            aria-label="Search products"
          />
        </form>
        <nav className="header-actions" aria-label="Account and cart">
          {authReady && (
            <button
              className="text-button"
              onClick={() => authenticated ? keycloak.logout() : keycloak.login()}
            >
              {authenticated ? `Hi, ${String(keycloak.tokenParsed?.given_name ?? 'Shopper')}` : 'Sign in'}
            </button>
          )}
          <button className="cart-button" onClick={() => setCartOpen(true)} aria-label={`Shopping bag with ${cartCount} items`}>
            Bag <span>{cartCount}</span>
          </button>
        </nav>
      </header>

      <main>
        <section className="hero">
          <div className="hero-copy">
            <p className="eyebrow">THE EDIT · 2026</p>
            <h1>Thoughtful finds.<br />Everyday ease.</h1>
            <p>Discover considered essentials designed to make your day feel a little better.</p>
            <button onClick={() => document.getElementById('catalog')?.scrollIntoView({ behavior: 'smooth' })}>
              Shop the collection <span>→</span>
            </button>
          </div>
          <div className="hero-art" aria-label="Curated lifestyle products">
            <div className="hero-orbit orbit-one" />
            <div className="hero-orbit orbit-two" />
            <img src="https://images.unsplash.com/photo-1526170375885-4d8ecf77b99f?auto=format&fit=crop&w=1200&q=85" alt="Camera on a clean desk" />
          </div>
        </section>

        <section className="catalog" id="catalog">
          <div className="section-heading">
            <div>
              <p className="eyebrow">CURATED FOR YOU</p>
              <h2>Explore the collection</h2>
            </div>
            {usingDemoData && <span className="demo-pill">Preview catalog · connect backend for live inventory</span>}
          </div>

          <div className="category-tabs" role="tablist" aria-label="Product categories">
            {categories.map((item) => (
              <button
                key={item.id}
                role="tab"
                aria-selected={category === item.id}
                className={category === item.id ? 'active' : ''}
                onClick={() => {
                  setCategory(item.id);
                  setQuery('');
                }}
              >
                {item.name}
              </button>
            ))}
          </div>

          {loading ? (
            <div className="loading-grid" aria-label="Loading products">
              {[1, 2, 3, 4].map((item) => <div className="skeleton" key={item} />)}
            </div>
          ) : products.length ? (
            <div className="product-grid">
              {products.map((product) => {
                const price = product.salePrice ?? product.price;
                return (
                  <article className="product-card" key={product.id}>
                    <div className="product-image">
                      {product.salePrice && <span className="sale-badge">SALE</span>}
                      <img src={product.imageUrl || `https://picsum.photos/seed/${product.id}/700/700`} alt={product.name} />
                      <button
                        onClick={() => addToCart(product)}
                        disabled={product.stockQuantity === 0}
                        aria-label={`Add ${product.name} to bag`}
                      >
                        {product.stockQuantity === 0 ? 'Out of stock' : 'Add to bag'}
                      </button>
                    </div>
                    <div className="product-info">
                      <div>
                        <h3>{product.name}</h3>
                        <p>{product.sku}</p>
                      </div>
                      <p className="price">
                        {money.format(price)}
                        {product.salePrice && <del>{money.format(product.price)}</del>}
                      </p>
                    </div>
                  </article>
                );
              })}
            </div>
          ) : (
            <div className="empty-state">
              <h3>No products found</h3>
              <p>Try another search or category.</p>
            </div>
          )}
        </section>

        <section className="service-strip">
          <div><span>◇</span><strong>Free delivery</strong><small>On orders over $75</small></div>
          <div><span>↺</span><strong>Easy returns</strong><small>30-day return window</small></div>
          <div><span>♧</span><strong>Secure checkout</strong><small>Protected payments</small></div>
          <div><span>♡</span><strong>Here to help</strong><small>AI-assisted shopping</small></div>
        </section>
      </main>

      <footer>
        <a className="brand" href="/"><span className="brand-mark">S</span><span>ShopEase</span></a>
        <p>Better things for everyday living.</p>
        <small>© 2026 ShopEase</small>
      </footer>

      <button className="assistant-trigger" onClick={() => setAssistantOpen(!assistantOpen)} aria-label="Open shopping assistant">
        {assistantOpen ? '×' : '✦'}
      </button>

      {assistantOpen && (
        <aside className="assistant-panel" aria-label="Shopping assistant">
          <div className="assistant-title"><span>✦</span><div><strong>Shopping assistant</strong><small>Powered by ShopEase AI</small></div></div>
          <div className="messages">
            {messages.map((message, index) => <p key={index} className={message.from}>{message.text}</p>)}
            {assistantBusy && <p className="assistant typing">Thinking…</p>}
          </div>
          <form onSubmit={sendAssistantMessage}>
            <input value={assistantInput} onChange={(event) => setAssistantInput(event.target.value)} placeholder="Ask about a product…" />
            <button disabled={assistantBusy} aria-label="Send message">↑</button>
          </form>
        </aside>
      )}

      {cartOpen && <div className="overlay" onClick={() => setCartOpen(false)} />}
      <aside className={`cart-drawer ${cartOpen ? 'open' : ''}`} aria-hidden={!cartOpen}>
        <div className="drawer-header">
          <div><p className="eyebrow">YOUR SELECTION</p><h2>Shopping bag</h2></div>
          <button onClick={() => setCartOpen(false)} aria-label="Close bag">×</button>
        </div>
        <div className="cart-lines">
          {cart.length === 0 ? (
            <div className="empty-state"><h3>Your bag is empty</h3><p>Explore the collection to find something you love.</p></div>
          ) : cart.map((line) => (
            <div className="cart-line" key={line.id}>
              <img src={line.imageUrl || `https://picsum.photos/seed/${line.id}/200`} alt="" />
              <div><strong>{line.name}</strong><small>{line.sku}</small>
                <div className="quantity">
                  <button onClick={() => changeQuantity(line.id, -1)}>−</button>
                  <span>{line.quantity}</span>
                  <button onClick={() => changeQuantity(line.id, 1)}>+</button>
                </div>
              </div>
              <b>{money.format((line.salePrice ?? line.price) * line.quantity)}</b>
            </div>
          ))}
        </div>
        {cart.length > 0 && <div className="cart-summary">
          <div><span>Subtotal</span><strong>{money.format(subtotal)}</strong></div>
          <small>Shipping and taxes calculated at checkout.</small>
          <button onClick={beginCheckout}>{authenticated ? 'Continue to checkout' : 'Sign in to checkout'} <span>→</span></button>
        </div>}
      </aside>

      {checkoutOpen && (
        <div className="modal-wrap">
          <div className="overlay" onClick={() => !checkoutBusy && setCheckoutOpen(false)} />
          <form className="checkout-modal" onSubmit={submitOrder}>
            <button type="button" className="modal-close" onClick={() => setCheckoutOpen(false)}>×</button>
            <p className="eyebrow">SECURE CHECKOUT</p>
            <h2>Where should we send it?</h2>
            <label>Shipping address
              <textarea required minLength={10} value={shippingAddress} onChange={(event) => setShippingAddress(event.target.value)} placeholder="Street, city, state, postal code" />
            </label>
            <div className="checkout-total"><span>Order total</span><strong>{money.format(subtotal)}</strong></div>
            <button disabled={checkoutBusy}>{checkoutBusy ? 'Placing order…' : 'Place order'}</button>
          </form>
        </div>
      )}

      {notice && <div className="toast" role="status">{notice}</div>}
    </div>
  );
}

export default App;
