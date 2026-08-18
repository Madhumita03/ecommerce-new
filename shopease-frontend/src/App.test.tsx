import React from 'react';
import { render, screen } from '@testing-library/react';

jest.mock('keycloak-js', () => ({
  __esModule: true,
  default: jest.fn().mockImplementation(() => ({
    init: jest.fn().mockResolvedValue(false),
    login: jest.fn(),
    logout: jest.fn(),
  })),
}));

import App from './App';

beforeEach(() => {
  global.fetch = jest.fn().mockRejectedValue(new Error('offline'));
  localStorage.clear();
});

test('renders the ShopEase storefront', async () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: /thoughtful finds/i })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /shopping bag/i })).toBeInTheDocument();
  expect(await screen.findByText(/preview catalog/i)).toBeInTheDocument();
});
