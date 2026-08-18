export const API_URL = process.env.REACT_APP_API_URL ?? 'http://localhost:8080';

export interface Product {
  id: string;
  name: string;
  sku: string;
  price: number;
  salePrice?: number | null;
  imageUrl?: string | null;
  stockQuantity: number;
  status: string;
  description?: string;
  categoryId?: number;
}

export interface CartLine extends Product {
  quantity: number;
}

export const fallbackProducts: Product[] = [
  { id: '7dd7b2b5-c184-4db4-bdb7-fc1ab918c764', name: 'QuietWave Headphones', sku: 'QW-100', price: 349, salePrice: 299, stockQuantity: 18, status: 'ACTIVE', categoryId: 1, imageUrl: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=900&q=80' },
  { id: '93a7e3b9-a54c-4a47-b5d7-68ec9fb635d4', name: 'Aero Smart Watch', sku: 'ASW-22', price: 249, salePrice: 219, stockQuantity: 12, status: 'ACTIVE', categoryId: 1, imageUrl: 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?auto=format&fit=crop&w=900&q=80' },
  { id: 'e3a31426-a956-458a-ad7e-f86aa68ab581', name: 'Everyday Sneakers', sku: 'ES-440', price: 119, stockQuantity: 24, status: 'ACTIVE', categoryId: 2, imageUrl: 'https://images.unsplash.com/photo-1542291026-7eec264c27ff?auto=format&fit=crop&w=900&q=80' },
  { id: '1dc00c3f-cbc9-4981-9209-a2b47f66cc87', name: 'Minimal Desk Lamp', sku: 'MDL-08', price: 89, salePrice: 72, stockQuantity: 9, status: 'ACTIVE', categoryId: 3, imageUrl: 'https://images.unsplash.com/photo-1507473885765-e6ed057f782c?auto=format&fit=crop&w=900&q=80' },
  { id: '033b5216-f7ed-46c8-89f8-fd3e67d8dcaf', name: 'Weekender Backpack', sku: 'WB-310', price: 139, stockQuantity: 16, status: 'ACTIVE', categoryId: 2, imageUrl: 'https://images.unsplash.com/photo-1553062407-98eeb64c6a62?auto=format&fit=crop&w=900&q=80' },
  { id: '862b0c20-3196-4114-97c2-6f28dd78d604', name: 'Ceramic Pour-Over Set', sku: 'CPO-12', price: 64, stockQuantity: 20, status: 'ACTIVE', categoryId: 3, imageUrl: 'https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?auto=format&fit=crop&w=900&q=80' },
];

async function request<T>(path: string, init?: RequestInit, token?: string): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed with status ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export async function getProducts(categoryId: number): Promise<Product[]> {
  const page = await request<{ content: Product[] }>(`/products?categoryId=${categoryId}&size=24`);
  return page.content;
}

export async function searchProducts(query: string): Promise<Product[]> {
  return request<Product[]>(`/products/search?q=${encodeURIComponent(query)}&size=24`);
}

export async function createOrder(
  lines: CartLine[],
  shippingAddress: string,
  identity: { userId: string; email: string },
  token: string,
) {
  return request<{ id: string; status: string }>('/orders', {
    method: 'POST',
    body: JSON.stringify({
      userId: identity.userId,
      userEmail: identity.email,
      shippingAddress,
      items: lines.map((line) => ({
        productId: line.id,
        quantity: line.quantity,
      })),
    }),
  }, token);
}

export async function askAssistant(sessionId: string, message: string, token?: string) {
  return request<{ response: string }>('/ai/chat', {
    method: 'POST',
    body: JSON.stringify({ sessionId, message }),
  }, token);
}
